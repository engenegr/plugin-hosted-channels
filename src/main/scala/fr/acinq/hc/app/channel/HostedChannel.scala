package fr.acinq.hc.app.channel

import akka.pattern._
import akka.actor.{Actor, ActorRef, ExtendedActorSystem, FSM, PoisonPill, Props, Terminated}
import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Origin.LocalCold
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version3.HCProtocolCodecs
import fr.acinq.eclair.wire.protocol._
import fr.acinq.hc.app.Tools.{DuplicateHandler, DuplicateShortId}
import fr.acinq.hc.app._
import fr.acinq.hc.app.db.Blocking.{span, timeout}
import fr.acinq.hc.app.db.HostedChannelsDb
import fr.acinq.hc.app.network.{HostedSync, OperationalData, PHC, PreimageBroadcastCatcher}

import scodec.bits.ByteVector
import java.util.UUID
import scala.concurrent.Await
import scala.util.{Failure, Success}


object HostedChannel {
  case class SendAnnouncements(force: Boolean)
}

class HostedChannel(kit: Kit, remoteNodeId: Crypto.PublicKey, channelsDb: HostedChannelsDb, hostedSync: ActorRef, cfg: Config) extends FSMDiagnosticActorLogging[ChannelState, HostedData] {

  lazy val channelId: ByteVector32 = Tools.hostedChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  lazy val shortChannelId: ShortChannelId = Tools.hostedShortChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  startTimerWithFixedDelay("SendAnnouncements", HostedChannel.SendAnnouncements(force = false), PHC.tickAnnounceThreshold)

  context.system.eventStream.subscribe(channel = classOf[PreimageBroadcastCatcher.BroadcastedPreimage], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[HostedSync.RouterIsOperational], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[CurrentBlockHeight], subscriber = self)

  startWith(OFFLINE, HC_NOTHING)

  when(OFFLINE) {
    case Event(cmd: HC_CMD_RESTORE, HC_NOTHING) =>
      val localLCSS = cmd.remoteData.lastCrossSignedState.reverse
      val htlcSet = localLCSS.incomingHtlcs.map(IncomingHtlc).toSet[DirectedHtlc] ++ localLCSS.outgoingHtlcs.map(OutgoingHtlc)
      val fakeOrigins: Map[Long, LocalCold] = localLCSS.incomingHtlcs.map(_.id).zip(LazyList continually UUID.randomUUID map LocalCold).toMap
      val data1 = restoreEmptyData(localLCSS).modify(_.commitments.localSpec.htlcs).setTo(htlcSet).modify(_.commitments.originChannels).setTo(fakeOrigins)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd)

    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) => stay using data

    case Event(Worker.HCPeerConnected, HC_NOTHING) => goto(SYNCING)

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost =>
      // Host is the one who awaits for client InvokeHostedChannel or Error on reconnect
      if (data.errorExt.isDefined) goto(CLOSED) else goto(SYNCING)

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) =>
      // Client is the one who sends either an Error or InvokeHostedChannel on reconnect
      if (data.localErrors.nonEmpty) goto(CLOSED) SendingHasChannelId data.localErrors.head.error
      else if (data.remoteError.isDefined) goto(CLOSED) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER)
      else goto(SYNCING) SendingHosted InvokeHostedChannel(kit.nodeParams.chainHash, data.commitments.lastCrossSignedState.refundScriptPubKey)

    case Event(Worker.TickRemoveIdleChannels, HC_NOTHING) => stop(FSM.Normal)

    case Event(Worker.TickRemoveIdleChannels, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost && data.pendingHtlcs.isEmpty => stop(FSM.Normal)

    // Prevent leaving OFFLINE state

    case Event(resize: ResizeChannel, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost => processResizeProposal(stay, resize, data)

    case Event(cmd: CurrentBlockHeight, data: HC_DATA_ESTABLISHED) => processBlockCount(stay, cmd.blockHeight.toLong, data)

    case Event(fulfill: UpdateFulfillHtlc, data: HC_DATA_ESTABLISHED) => processIncomingFulfill(stay, fulfill, data)

    case Event(error: Error, data: HC_DATA_ESTABLISHED) => processRemoteError(stay, error, data)

    case Event(cmd: HC_CMD_EXTERNAL_FULFILL, data: HC_DATA_ESTABLISHED) => processExternalFulfill(stay, cmd, data)

    case Event(cmd: HC_CMD_SUSPEND, data: HC_DATA_ESTABLISHED) =>
      val (data1, _) = withLocalError(data, ErrorCodes.ERR_HOSTED_MANUAL_SUSPEND)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd)
  }

  when(SYNCING) {
    case Event(cmd @ HC_CMD_LOCAL_INVOKE(_, scriptPubKey, secret), HC_NOTHING) =>
      val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, scriptPubKey, secret)
      stay using HC_DATA_CLIENT_WAIT_HOST_INIT(scriptPubKey) SendingHosted invokeMsg replying CMDResSuccess(cmd)

    case Event(remoteInvoke: InvokeHostedChannel, HC_NOTHING) =>
      val isWrongChain = kit.nodeParams.chainHash != remoteInvoke.chainHash
      val isValidFinalScriptPubkey = Helpers.Closing.MutualClose.isValidFinalScriptPubkey(remoteInvoke.refundScriptPubKey, allowAnySegwit = false)
      if (isWrongChain) stop(FSM.Normal) SendingHasChannelId Error(channelId, InvalidChainHash(channelId, kit.nodeParams.chainHash, remoteInvoke.chainHash).getMessage)
      else if (!isValidFinalScriptPubkey) stop(FSM.Normal) SendingHasChannelId Error(channelId, InvalidFinalScript(channelId).getMessage)
      else stay using HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(remoteInvoke) SendingHosted cfg.vals.hcParams.initMsg

    case Event(hostInit: InitHostedChannel, data: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      val fullySignedLCSS = LastCrossSignedState(isHost = false, data.refundScriptPubKey, initHostedChannel = hostInit, currentBlockDay,
        localBalanceMsat = hostInit.initialClientBalanceMsat, remoteBalanceMsat = hostInit.channelCapacityMsat - hostInit.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L,
        incomingHtlcs = Nil, outgoingHtlcs = Nil, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

      if (hostInit.initialClientBalanceMsat > hostInit.channelCapacityMsat) stop(FSM.Normal) SendingHasChannelId Error(channelId, "Proposed init balance for us is larger than capacity")
      else stay using HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(restoreEmptyData(fullySignedLCSS).commitments) SendingHosted fullySignedLCSS.stateUpdate

    case Event(clientSU: StateUpdate, data: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val fullySignedLCSS = LastCrossSignedState(isHost = true, data.invoke.refundScriptPubKey, initHostedChannel = cfg.vals.hcParams.initMsg, clientSU.blockDay,
        localBalanceMsat = cfg.vals.hcParams.initMsg.channelCapacityMsat, remoteBalanceMsat = 0L.msat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil,
        outgoingHtlcs = Nil, remoteSigOfLocal = clientSU.localSigOfRemoteLCSS, localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

      val dh = new DuplicateHandler[HC_DATA_ESTABLISHED] {
        def insert(data: HC_DATA_ESTABLISHED): Boolean =
          channelsDb addNewChannel data
      }

      val data1 = restoreEmptyData(fullySignedLCSS)
      val isLocalSigOk = fullySignedLCSS.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(clientSU)

      if (isBlockDayWrong) stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
      else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      else {
        dh.execute(data1) match {
          case Failure(DuplicateShortId) =>
            log.info(s"PLGN PHC, DuplicateShortId when storing new HC, peer=$remoteNodeId")
            stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)

          case Success(true) =>
            log.info(s"PLGN PHC, stored new HC with peer=$remoteNodeId")
            channelsDb.updateSecretById(remoteNodeId, data.invoke.finalSecret)
            goto(NORMAL) using data1 SendingHosted fullySignedLCSS.stateUpdate

          case _ =>
            log.info(s"PLGN PHC, database error when trying to store new HC, peer=$remoteNodeId")
            stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
        }
      }

    case Event(hostSU: StateUpdate, data: HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commitments.lastCrossSignedState.copy(remoteSigOfLocal = hostSU.localSigOfRemoteLCSS)
      val isRemoteUpdatesMismatch = data.commitments.lastCrossSignedState.remoteUpdates != hostSU.localUpdates
      val isLocalUpdatesMismatch = data.commitments.lastCrossSignedState.localUpdates != hostSU.remoteUpdates
      val isLocalSigOk = fullySignedLCSS.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(hostSU)

      if (isBlockDayWrong) stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
      else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      else if (isRemoteUpdatesMismatch) stop(FSM.Normal) SendingHasChannelId Error(channelId, "Proposed remote/local update number mismatch")
      else if (isLocalUpdatesMismatch) stop(FSM.Normal) SendingHasChannelId Error(channelId, "Proposed local/remote update number mismatch")
      else goto(NORMAL) StoringAndUsing restoreEmptyData(fullySignedLCSS)

    // MISSING CHANNEL

    case Event(_: LastCrossSignedState, _: HC_DATA_CLIENT_WAIT_HOST_INIT) => stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_MISSING_CHANNEL)

    case Event(_: LastCrossSignedState, _: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) => stop(FSM.Normal) SendingHasChannelId Error(channelId, ErrorCodes.ERR_MISSING_CHANNEL)

    // NORMAL PATHWAY

    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost => stay SendingHosted data.commitments.lastCrossSignedState

    case Event(_: InitHostedChannel, data: HC_DATA_ESTABLISHED) if !data.commitments.lastCrossSignedState.isHost => stay SendingHosted data.commitments.lastCrossSignedState

    case Event(remoteLCSS: LastCrossSignedState, data: HC_DATA_ESTABLISHED) =>
      val localLCSS: LastCrossSignedState = data.commitments.lastCrossSignedState // In any case our LCSS is the current one
      val data1 = data.resizeProposal.filter(_ isRemoteResized remoteLCSS).map(data.withResize).getOrElse(data) // But they may have a resized one
      val weAreEven = localLCSS.remoteUpdates == remoteLCSS.localUpdates && localLCSS.localUpdates == remoteLCSS.remoteUpdates
      val weAreAhead = localLCSS.remoteUpdates > remoteLCSS.localUpdates || localLCSS.localUpdates > remoteLCSS.remoteUpdates
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)

      if (!isRemoteSigOk) {
        val (finalData, error) = withLocalError(data1, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        goto(CLOSED) StoringAndUsing finalData SendingHasChannelId error
      } else if (!isLocalSigOk) {
        val (finalData, error) = withLocalError(data1, ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
        goto(CLOSED) StoringAndUsing finalData SendingHasChannelId error
      } else if (weAreEven || weAreAhead) {
        val retransmit = Vector(localLCSS) ++ data1.resizeProposal
        val finalData = data1.copy(commitments = data1.commitments.copy(nextRemoteUpdates = Nil), overrideProposal = None)
        goto(NORMAL) using finalData SendingManyHosted retransmit SendingManyHasChannelId data1.commitments.nextLocalUpdates Receiving CMD_SIGN(None)
      } else {
        val localUpdatesAcked = remoteLCSS.remoteUpdates - localLCSS.localUpdates
        val remoteUpdatesAcked = remoteLCSS.localUpdates - localLCSS.remoteUpdates

        val remoteUpdatesAccountedByLocal = data1.commitments.nextRemoteUpdates take remoteUpdatesAcked.toInt
        val localUpdatesAccountedByRemote = data1.commitments.nextLocalUpdates take localUpdatesAcked.toInt
        val localUpdatesLeftover = data1.commitments.nextLocalUpdates drop localUpdatesAcked.toInt

        val commits1 = data1.commitments.copy(nextLocalUpdates = localUpdatesAccountedByRemote, nextRemoteUpdates = remoteUpdatesAccountedByLocal)
        val restoredLCSS = commits1.nextLocalUnsignedLCSS(remoteLCSS.blockDay).copy(localSigOfRemote = remoteLCSS.remoteSigOfLocal, remoteSigOfLocal = remoteLCSS.localSigOfRemote)

        if (restoredLCSS.reverse == remoteLCSS) {
          val retransmit = Vector(restoredLCSS) ++ data1.resizeProposal
          val restoredCommits = clearOrigin(commits1.copy(lastCrossSignedState = restoredLCSS, localSpec = commits1.nextLocalSpec, nextLocalUpdates = localUpdatesLeftover, nextRemoteUpdates = Nil), data1.commitments)
          goto(NORMAL) StoringAndUsing data1.copy(commitments = restoredCommits) RelayingRemoteUpdates commits1 SendingManyHosted retransmit SendingManyHasChannelId localUpdatesLeftover Receiving CMD_SIGN(None)
        } else {
          val (data2, error) = withLocalError(data1, ErrorCodes.ERR_MISSING_CHANNEL)
          goto(CLOSED) StoringAndUsing data2 SendingHasChannelId error
        }
      }
  }

  when(NORMAL) {

    // PHC announcements

    case Event(_: HostedSync.RouterIsOperational, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      // PHC with remote peer may become NORMAL sooner than our PHC router becomes operational
      manageUpdates(data)
      stay

    case Event(HostedChannel.SendAnnouncements(force), data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      context.system.eventStream publish makeLocalUpdateEvent(update1, data.commitments)
      val data1 = data.copy(channelUpdate = update1)

      data1.channelAnnouncement match {
        case None => stay StoringAndUsing data1 SendingHosted Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = true)
        case Some(announce) if force || data.shouldRebroadcastAnnounce => stay StoringAndUsing data1 Announcing announce Announcing update1
        case _ => stay StoringAndUsing data1 Announcing update1
      }

    case Event(remoteSig: AnnouncementSignature, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val localSig = Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = false)
      val announce = Tools.makePHCAnnouncement(kit.nodeParams, localSig, remoteSig, shortChannelId, remoteNodeId)
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1 = data.copy(channelAnnouncement = Some(announce), channelUpdate = update1)
      context.system.eventStream publish makeLocalUpdateEvent(update1, data.commitments)
      val isSigOK = Announcements.checkSigs(announce)

      if (isSigOK && remoteSig.wantsReply) {
        log.info(s"PLGN PHC, announcing PHC and sending sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 SendingHosted localSig Announcing announce Announcing data1.channelUpdate
      } else if (isSigOK) {
        log.info(s"PLGN PHC, announcing PHC without sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 Announcing announce Announcing data1.channelUpdate
      } else {
        log.info(s"PLGN PHC, announce sig check failed, peer=$remoteNodeId")
        stay
      }

    case Event(HC_CMD_PUBLIC(remoteNodeId, false), data: HC_DATA_ESTABLISHED) =>
      val syncData = Await.result(hostedSync ? HostedSync.GetHostedSyncData, span).asInstanceOf[OperationalData]
      val notEnoughNormalChannels = syncData.tooFewNormalChans(kit.nodeParams.nodeId, remoteNodeId, cfg.vals.phcConfig)
      val tooManyPublicHostedChannels = syncData.phcNetwork.tooManyPHCs(kit.nodeParams.nodeId, remoteNodeId, cfg.vals.phcConfig)
      if (tooManyPublicHostedChannels.isDefined) stay replying CMDResFailure(s"Can't proceed: nodeId=${tooManyPublicHostedChannels.get} has too many PHCs already, max=${cfg.vals.phcConfig.maxPerNode}")
      else if (notEnoughNormalChannels.isDefined) stay replying CMDResFailure(s"Can't proceed: nodeId=${notEnoughNormalChannels.get} has too few normal channels, min=${cfg.vals.phcConfig.minNormalChans}")
      else if (cfg.vals.phcConfig.minCapacity > data.commitments.capacity) stay replying CMDResFailure(s"Can't proceed: HC capacity is below min=${cfg.vals.phcConfig.minCapacity}")
      else if (cfg.vals.phcConfig.maxCapacity < data.commitments.capacity) stay replying CMDResFailure(s"Can't proceed: HC capacity is above max=${cfg.vals.phcConfig.maxCapacity}")
      else stay Receiving HC_CMD_PUBLIC(remoteNodeId, force = true)

    case Event(cmd: HC_CMD_PUBLIC, data: HC_DATA_ESTABLISHED) =>
      val data1 = data.modify(_.commitments.announceChannel).setTo(true).copy(channelAnnouncement = None)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd) Receiving HostedChannel.SendAnnouncements(force = false)

    case Event(cmd: HC_CMD_PRIVATE, data: HC_DATA_ESTABLISHED) =>
      // This does not immediately affect PHC graph and other HC nodes will keep this channel for `PHC.staleThreshold` days
      val data1 = data.modify(_.commitments.announceChannel).setTo(false).copy(channelAnnouncement = None)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd)

    // Payments

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendAdd(cmd, kit.nodeParams.currentBlockHeight) match {
        case Right((commits1, add)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) AckingAddSuccess cmd SendingHasChannelId add Receiving CMD_SIGN(None)
        case Right((commits1, add)) => stay StoringAndUsing data.copy(commitments = commits1) AckingAddSuccess cmd SendingHasChannelId add
        case Left(cause) => ackAddFail(cmd, cause, data.channelUpdate)
      }

    // IMPORTANT: Peer adding and failing HTLCs is only accepted in NORMAL

    case Event(add: UpdateAddHtlc, data: HC_DATA_ESTABLISHED) => processRemoteResolve(data.commitments.receiveAdd(add), data)

    case Event(fail: UpdateFailHtlc, data: HC_DATA_ESTABLISHED) => processRemoteResolve(data.commitments.receiveFail(fail), data)

    case Event(fail: UpdateFailMalformedHtlc, data: HC_DATA_ESTABLISHED) => processRemoteResolve(data.commitments.receiveFailMalformed(fail), data)

    case Event(_: CMD_SIGN, data: HC_DATA_ESTABLISHED) if data.commitments.nextLocalUpdates.nonEmpty || data.resizeProposal.isDefined =>
      val nextLocalLCSS = data.resizeProposal.map(data.withResize).getOrElse(data).commitments.nextLocalUnsignedLCSS(currentBlockDay)
      stay SendingHosted nextLocalLCSS.withLocalSigOfRemote(kit.nodeParams.privateKey).stateUpdate

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) if remoteSU.localSigOfRemoteLCSS != data.commitments.lastCrossSignedState.remoteSigOfLocal => attemptStateUpdate(remoteSU, data)
  }

  when(CLOSED) {
    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost =>
      if (data.localErrors.nonEmpty) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId data.localErrors.head.error
      else if (data.remoteError.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId Error(channelId, ErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER)
      else stay

    // OVERRIDING

    case Event(remoteSO: StateOverride, data: HC_DATA_ESTABLISHED) if !data.commitments.lastCrossSignedState.isHost =>
      stay StoringAndUsing data.copy(overrideProposal = Some(remoteSO))

    case Event(cmd: HC_CMD_OVERRIDE_ACCEPT, data: HC_DATA_ESTABLISHED) =>
      if (data.errorExt.isEmpty) stay replying CMDResFailure("Overriding declined: channel is in normal state")
      else if (data.commitments.lastCrossSignedState.isHost) stay replying CMDResFailure("Overriding declined: only client side can accept override")
      else if (data.overrideProposal.isEmpty) stay replying CMDResFailure("Overriding declined: no override proposal from host is found")
      else {
        val remoteSO = data.overrideProposal.get
        val newLocalBalance = data.commitments.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat
        val completeLocalLCSS = data.commitments.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = newLocalBalance,
          remoteBalanceMsat = remoteSO.localBalanceMsat, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates, blockDay = remoteSO.blockDay,
          remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(kit.nodeParams.privateKey)

        val isRemoteSigOk = completeLocalLCSS.verifyRemoteSig(remoteNodeId)
        if (remoteSO.localUpdates < data.commitments.lastCrossSignedState.remoteUpdates) stay replying CMDResFailure("Overridden local update number is less than remote")
        else if (remoteSO.remoteUpdates < data.commitments.lastCrossSignedState.localUpdates) stay replying CMDResFailure("Overridden remote update number is less than local")
        else if (remoteSO.blockDay < data.commitments.lastCrossSignedState.blockDay) stay replying CMDResFailure("Overridden remote blockday is less than local")
        else if (newLocalBalance > data.commitments.capacity) stay replying CMDResFailure("Overriding declined: new local balance exceeds capacity")
        else if (newLocalBalance < 0L.msat) stay replying CMDResFailure("Overriding declined: new local balance is less than zero")
        else if (!isRemoteSigOk) stay replying CMDResFailure("Remote override signature is wrong")
        else {
          failTimedoutOutgoing(data.timedOutOutgoingHtlcs(Long.MaxValue), data)
          goto(NORMAL) StoringAndUsing restoreEmptyData(completeLocalLCSS) replying CMDResSuccess(cmd) SendingHosted completeLocalLCSS.stateUpdate
        }
      }

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED)
      if data.commitments.lastCrossSignedState.isHost && data.overrideProposal.isDefined =>
      val StateOverride(savedBlockDay, savedLocalBalanceMsat, savedLocalUpdates, savedRemoteUpdates, _) = data.overrideProposal.get
      val lcss = makeOverridingLocallySignedLCSS(data.commitments, savedLocalBalanceMsat, savedLocalUpdates, savedRemoteUpdates, savedBlockDay)
      val completeLocallySignedLCSS = lcss.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
      val isRemoteSigOk = completeLocallySignedLCSS.verifyRemoteSig(remoteNodeId)

      if (remoteSU.blockDay != savedBlockDay) stay SendingHasChannelId Error(channelId, "Override blockday is not acceptable")
      else if (remoteSU.remoteUpdates != savedLocalUpdates) stay SendingHasChannelId Error(channelId, "Override remote update number is wrong")
      else if (remoteSU.localUpdates != savedRemoteUpdates) stay SendingHasChannelId Error(channelId, "Override local update number is wrong")
      else if (!isRemoteSigOk) stay SendingHasChannelId Error(channelId, "Override signature is wrong")
      else {
        failTimedoutOutgoing(data.timedOutOutgoingHtlcs(Long.MaxValue), data)
        goto(NORMAL) StoringAndUsing restoreEmptyData(completeLocallySignedLCSS)
      }
  }

  whenUnhandled {
    case Event(resize: ResizeChannel, data: HC_DATA_ESTABLISHED) if data.commitments.lastCrossSignedState.isHost => processResizeProposal(goto(CLOSED), resize, data)

    case Event(cmd: CurrentBlockHeight, data: HC_DATA_ESTABLISHED) => processBlockCount(goto(CLOSED), cmd.blockHeight.toLong, data)

    case Event(fulfill: UpdateFulfillHtlc, data: HC_DATA_ESTABLISHED) => processIncomingFulfill(goto(CLOSED), fulfill, data)

    case Event(error: Error, data: HC_DATA_ESTABLISHED) => processRemoteError(goto(CLOSED), error, data)

    case Event(_: Error, _) => stop(FSM.Normal)

    case Event(Worker.HCPeerDisconnected, _: HC_DATA_ESTABLISHED) => goto(OFFLINE)

    case Event(Worker.HCPeerDisconnected, _) => stop(FSM.Normal)

    case Event(cmd: CMD_FULFILL_HTLC, data: HC_DATA_ESTABLISHED) => resolveSettlementCommand(data.commitments.sendFulfill(cmd), data, cmd, cmd.commit)

    case Event(cmd: CMD_FAIL_HTLC, data: HC_DATA_ESTABLISHED) => resolveSettlementCommand(data.commitments.sendFail(cmd, kit.nodeParams.privateKey), data, cmd, cmd.commit)

    case Event(cmd: CMD_FAIL_MALFORMED_HTLC, data: HC_DATA_ESTABLISHED) => resolveSettlementCommand(data.commitments.sendFailMalformed(cmd), data, cmd, cmd.commit)

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      ackAddFail(cmd, ChannelUnavailable(channelId), data.channelUpdate)
      log.info(s"PLGN PHC, rejecting htlc in state=$stateName, peer=$remoteNodeId")
      stay

    // Scheduling override

    case Event(cmd: HC_CMD_OVERRIDE_PROPOSE, data: HC_DATA_ESTABLISHED) =>
      if (data.errorExt.isEmpty) stay replying CMDResFailure("Overriding declined: channel is in normal state")
      else if (!data.commitments.lastCrossSignedState.isHost) stay replying CMDResFailure("Overriding declined: only host side can initiate override")
      else if (cmd.newLocalBalance > data.commitments.capacity) stay replying CMDResFailure("Overriding declined: new local balance exceeds capacity")
      else if (cmd.newLocalBalance < 0L.msat) stay replying CMDResFailure("Overriding declined: new local balance is less than zero")
      else {
        log.info(s"PLGN PHC, scheduling override proposal for peer=$remoteNodeId")
        val newLocalUpdates = data.commitments.lastCrossSignedState.localUpdates + data.commitments.nextLocalUpdates.size + 1
        val newRemoteUpdates = data.commitments.lastCrossSignedState.remoteUpdates + data.commitments.nextRemoteUpdates.size + 1
        val overrideLCSS = makeOverridingLocallySignedLCSS(data.commitments, cmd.newLocalBalance, newLocalUpdates, newRemoteUpdates, currentBlockDay)
        val localSO = StateOverride(overrideLCSS.blockDay, overrideLCSS.localBalanceMsat, overrideLCSS.localUpdates, overrideLCSS.remoteUpdates, overrideLCSS.localSigOfRemote)
        stay StoringAndUsing data.copy(overrideProposal = Some(localSO)) replying CMDResSuccess(cmd) SendingHosted localSO
      }

    // Misc

    case Event(cmd: HC_CMD_SUSPEND, data: HC_DATA_ESTABLISHED) =>
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_MANUAL_SUSPEND)
      goto(CLOSED) StoringAndUsing data1 replying CMDResSuccess(cmd) SendingHasChannelId error

    case Event(PreimageBroadcastCatcher.BroadcastedPreimage(hash, preimage), data: HC_DATA_ESTABLISHED) =>
      // We have a preimage, but we also need a payment id to fulfill it properly, see if we have any pending payments with given hash
      val toFulfillCmd: UpdateAddHtlc => HC_CMD_EXTERNAL_FULFILL = add => HC_CMD_EXTERNAL_FULFILL(remoteNodeId, add.id, preimage)
      data.outgoingHtlcsByHash(hash).map(toFulfillCmd).foreach(externalFulfillCmd => self ! externalFulfillCmd)
      stay

    case Event(cmd: HC_CMD_EXTERNAL_FULFILL, data: HC_DATA_ESTABLISHED) => processExternalFulfill(goto(CLOSED), cmd, data)

    case Event(_: HC_CMD_GET_INFO, data: HC_DATA_ESTABLISHED) => stay replying CMDResInfo(stateName, data, data.commitments.nextLocalSpec)
    case Event(_: HC_CMD_GET_ALL_CHANNELS, data: HC_DATA_ESTABLISHED) => stay replying CMDResInfo(stateName, data, data.commitments.nextLocalSpec)

    case Event(cmd: CMD_GET_CHANNEL_INFO, _) =>
      val msg = new IllegalArgumentException("Non-standard channel")
      replyToCommand(RES_FAILURE(cmd, msg), cmd)
      stay

    case Event(cmd: CMD_UPDATE_RELAY_FEE, _) =>
      // Must reply with something for API to work well
      replyToCommand(RES_SUCCESS(cmd, channelId), cmd)
      stay

    case Event(cmd: HC_CMD_RESIZE, data: HC_DATA_ESTABLISHED) =>
      val msg = ResizeChannel(cmd.newCapacity).sign(kit.nodeParams.privateKey)
      if (data.errorExt.nonEmpty) stay replying CMDResFailure("Resizing declined: channel is in error state")
      else if (!data.isResizeSupported) stay replying CMDResFailure("Resizing declined: channel does not support resizing")
      else if (data.resizeProposal.nonEmpty) stay replying CMDResFailure("Resizing declined: channel is already being resized")
      else if (data.commitments.lastCrossSignedState.isHost) stay replying CMDResFailure("Resizing declined: only client can initiate resizing")
      else if (data.commitments.capacity > msg.newCapacity) stay replying CMDResFailure("Resizing declined: new capacity must be larger than current capacity")
      else if (cfg.vals.phcConfig.maxCapacity < msg.newCapacity) stay replying CMDResFailure("Resizing declined: new capacity must not exceed max allowed capacity")
      else stay StoringAndUsing data.copy(resizeProposal = Some(msg), overrideProposal = None) SendingHosted msg replying CMDResSuccess(cmd) Receiving CMD_SIGN(None)

    case Event(cmd: HasRemoteNodeIdHostedCommand, _) => stay replying CMDResFailure(s"Can not process cmd=${cmd.getClass.getName} in state=$stateName")

    case _ => stay
  }

  onTransition {
    case state -> nextState =>
      val connectionOpt = HC.remoteNode2Connection.get(remoteNodeId)

      (connectionOpt, state, nextState, nextStateData) match {
        case (Some(connection), SYNCING | CLOSED, NORMAL, d1: HC_DATA_ESTABLISHED) =>
          if (d1.commitments.announceChannel) manageUpdates(d1) else connection sendRoutingMsg d1.channelUpdate
          context.system.eventStream publish HostedChannelRestored(self, channelId, connection.info.peer, remoteNodeId)
          context.system.eventStream publish ChannelIdAssigned(self, remoteNodeId, temporaryChannelId = ByteVector32.Zeroes, channelId)
          context.system.eventStream publish ShortChannelIdAssigned(self, channelId, shortChannelId, previousShortChannelId = None)
          context.system.eventStream publish makeLocalUpdateEvent(d1.channelUpdate, d1.commitments)

        case (_, NORMAL, OFFLINE | CLOSED, _) =>
          context.system.eventStream publish LocalChannelDown(self, channelId, shortChannelId, remoteNodeId)
        case _ =>
      }

      (state, nextState, nextStateData) match {
        case (OFFLINE | SYNCING, NORMAL | CLOSED, d1: HC_DATA_ESTABLISHED) if d1.pendingHtlcs.nonEmpty =>
          val dbPending = PendingCommandsDb.getSettlementCommands(kit.nodeParams.db.pendingCommands, channelId)(log)
          for (failOrFulfillCommand <- dbPending) self ! failOrFulfillCommand
          if (dbPending.nonEmpty) self ! CMD_SIGN(None)
        case _ =>
      }

      (connectionOpt, state, nextState, nextStateData) match {
        case (Some(connection), OFFLINE | SYNCING, NORMAL | CLOSED, d1: HC_DATA_ESTABLISHED) =>
          context.system.eventStream publish ChannelStateChanged(self, channelId, connection.info.peer, remoteNodeId, state, nextState, Some(d1.commitments))
          for (overrideProposal <- d1.overrideProposal if d1.commitments.lastCrossSignedState.isHost) connection sendHostedChannelMsg overrideProposal
        case _ =>
      }

      (connectionOpt, state, nextState, stateData, nextStateData) match {
        case (Some(connection), OFFLINE | SYNCING, CLOSED, _, d1: HC_DATA_ESTABLISHED) =>
          // We may get fulfills for peer payments while offline when channel is in error state
          d1.commitments.pendingOutgoingFulfills.foreach(connection.sendHasChannelIdMsg)
        case _ =>
      }
  }

  type HostedFsmState = FSM.State[ChannelState, HostedData]

  implicit class FsmStateExt(state: HostedFsmState) {
    def SendingHasChannelId(message: HasChannelId): HostedFsmState = SendingManyHasChannelId(message :: Nil)
    def SendingHosted(message: HostedChannelMessage): HostedFsmState = SendingManyHosted(message :: Nil)

    def SendingManyHasChannelId(messages: Seq[HasChannelId] = Nil): HostedFsmState = {
      HC.remoteNode2Connection.get(remoteNodeId).foreach(messages foreach _.sendHasChannelIdMsg)
      state
    }

    def SendingManyHosted(messages: Seq[HostedChannelMessage] = Nil): HostedFsmState = {
      HC.remoteNode2Connection.get(remoteNodeId).foreach(messages foreach _.sendHostedChannelMsg)
      state
    }

    def AckingSuccess(command: HtlcSettlementCommand): HostedFsmState = {
      PendingCommandsDb.ackSettlementCommand(kit.nodeParams.db.pendingCommands, channelId, command)
      replyToCommand(RES_SUCCESS(command, channelId), command)
      state
    }

    def AckingAddSuccess(command: CMD_ADD_HTLC): HostedFsmState = {
      replyToCommand(RES_SUCCESS(command, channelId), command)
      state
    }

    def Announcing(message: AnnouncementMessage): HostedFsmState = {
      hostedSync ! HCProtocolCodecs.toUnknownAnnounceMessage(message, isGossip = true)
      state
    }

    def Receiving(message: Any): HostedFsmState = {
      self forward message
      state
    }

    def StoringAndUsing(data: HC_DATA_ESTABLISHED): HostedFsmState = {
      channelsDb.updateOrAddNewChannel(data)
      state using data
    }

    def RelayingRemoteUpdates(commits: HostedCommitments): HostedFsmState = {
      commits.nextRemoteUpdates.collect {
        case malformedFail: UpdateFailMalformedHtlc =>
          val origin = commits.originChannels(malformedFail.id)
          val outgoing = commits.localSpec.findOutgoingHtlcById(malformedFail.id).get
          kit.relayer ! RES_ADD_SETTLED(origin, outgoing.add, HtlcResult RemoteFailMalformed malformedFail)

        case fail: UpdateFailHtlc =>
          val origin = commits.originChannels(fail.id)
          val outgoing = commits.localSpec.findOutgoingHtlcById(fail.id).get
          kit.relayer ! RES_ADD_SETTLED(origin, outgoing.add, HtlcResult RemoteFail fail)

        case add: UpdateAddHtlc =>
          kit.relayer ! Relayer.RelayForward(add)
      }

      state
    }
  }

  initialize()

  def currentBlockDay: Long = kit.nodeParams.currentBlockHeight.toLong / 144

  def isBlockDayOutOfSync(remoteSU: StateUpdate): Boolean = math.abs(remoteSU.blockDay - currentBlockDay) > 1

  def makeLocalUpdateEvent(update: ChannelUpdate, commits: HostedCommitments): LocalChannelUpdate = LocalChannelUpdate(self, channelId, shortChannelId, remoteNodeId, None, update, commits)

  def makeChannelUpdate(localLCSS: LastCrossSignedState, enable: Boolean): ChannelUpdate =
    Announcements.makeChannelUpdate(kit.nodeParams.chainHash, kit.nodeParams.privateKey, remoteNodeId, shortChannelId, CltvExpiryDelta(cfg.vals.hcParams.cltvDeltaBlocks),
      cfg.vals.hcParams.htlcMinimum, cfg.vals.hcParams.feeBase, cfg.vals.hcParams.feeProportionalMillionths, localLCSS.initHostedChannel.channelCapacityMsat, enable)

  def makeOverridingLocallySignedLCSS(commits: HostedCommitments, newLocalBalance: MilliSatoshi, newLocalUpdates: Long, newRemoteUpdates: Long, overrideBlockDay: Long): LastCrossSignedState =
    commits.lastCrossSignedState.copy(localBalanceMsat = newLocalBalance, remoteBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - newLocalBalance, incomingHtlcs = Nil,
      outgoingHtlcs = Nil, localUpdates = newLocalUpdates, remoteUpdates = newRemoteUpdates, blockDay = overrideBlockDay, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

  def restoreEmptyData(localLCSS: LastCrossSignedState): HC_DATA_ESTABLISHED =
    HC_DATA_ESTABLISHED(HostedCommitments(localNodeId = kit.nodeParams.nodeId, remoteNodeId, channelId,
      CommitmentSpec(htlcs = Set.empty, FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat), originChannels = Map.empty,
      localLCSS, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, announceChannel = false), makeChannelUpdate(localLCSS, enable = true), localErrors = Nil)

  def withLocalError(data: HC_DATA_ESTABLISHED, errorCode: String): (HC_DATA_ESTABLISHED, Error) = {
    val localErrorExt: ErrorExt = ErrorExt generateFrom Error(channelId = channelId, msg = errorCode)
    val fulfillsAndFakeFails = data.commitments.nextRemoteUpdates.collect { case f: UpdateFulfillHtlc => f case f: UpdateFailHtlc if f.reason.isEmpty => f }
    val data1 = data.copy(commitments = data.commitments.copy(nextRemoteUpdates = fulfillsAndFakeFails), localErrors = localErrorExt :: data.localErrors)
    context.system.eventStream publish HCSuspended(remoteNodeId, data.commitments.lastCrossSignedState.isHost, isLocal = true, errorCode)
    (data1, localErrorExt.error)
  }

  def ackAddFail(cmd: CMD_ADD_HTLC, cause: ChannelException, channelUpdate: ChannelUpdate): HostedFsmState = {
    log.warning(s"PLGN PHC, ${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    replyToCommand(RES_ADD_FAILED(channelUpdate = Some(channelUpdate), t = cause, c = cmd), cmd)
    stay
  }

  // Disconnect in a special case where they send a resolution before it is cross-signed
  // This may happen if they have our earlier cross-signed state and we have got new commands while they were replying
  def processRemoteResolve(result: Either[ChannelException, HostedCommitments], data: HC_DATA_ESTABLISHED): HostedFsmState =
    result match {
      case Right(commits1) =>
        stay using data.copy(commitments = commits1)

      case Left(_: UnsignedHtlcResolve) =>
        val disconnect = Peer.Disconnect(remoteNodeId)
        val peer = HC.remoteNode2Connection.get(remoteNodeId)
        log.info(s"PLGN PHC, force-disconnecting peer=$remoteNodeId")
        peer.foreach(_.info.peer ! disconnect)
        goto(OFFLINE) StoringAndUsing data

      case Left(cause) =>
        val (data1, error) = withLocalError(data, cause.getMessage)
        goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
    }

  def failTimedoutOutgoing(localAdds: Set[UpdateAddHtlc], data: HC_DATA_ESTABLISHED): Unit = localAdds foreach { add =>
    log.info(s"PLGN PHC, failing timed out outgoing htlc, hash=${add.paymentHash}, peer=$remoteNodeId")
    val reasonChain = HtlcResult OnChainFail HtlcOverriddenByLocalCommit(channelId, htlc = add)
    kit.relayer ! RES_ADD_SETTLED(data.commitments.originChannels(add.id), add, reasonChain)
  }

  def clearOrigin(fresh: HostedCommitments, old: HostedCommitments): HostedCommitments = {
    val oldStateOutgoingHtlcIds = old.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id)
    val freshStateOutgoingHtlcIds = fresh.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id)
    val completedOutgoingHtlcs = oldStateOutgoingHtlcIds -- freshStateOutgoingHtlcIds
    fresh.copy(originChannels = fresh.originChannels -- completedOutgoingHtlcs)
  }

  // Prevent OFFLINE -> CLOSED jump by supplying a next state

  def processIncomingFulfill(errorState: FsmStateExt, fulfill: UpdateFulfillHtlc, data: HC_DATA_ESTABLISHED): HostedFsmState =
    data.commitments.receiveFulfill(fulfill) match {
      case Right((commits1, origin, htlc)) =>
        val result = HtlcResult.RemoteFulfill(fulfill)
        kit.relayer ! RES_ADD_SETTLED(origin, htlc, result)
        stay StoringAndUsing data.copy(commitments = commits1)
      case Left(cause) =>
        val (data1, error) = withLocalError(data, cause.getMessage)
        errorState StoringAndUsing data1 SendingHasChannelId error
    }

  def processRemoteError(errorState: FsmStateExt, remoteError: Error, data: HC_DATA_ESTABLISHED): HostedFsmState = if (data.remoteError.isEmpty) {
    val fulfillsAndFakeFails = data.commitments.nextRemoteUpdates.collect { case f: UpdateFulfillHtlc => f case f: UpdateFailHtlc if f.reason.isEmpty => f }
    val data1 = data.copy(commitments = data.commitments.copy(nextRemoteUpdates = fulfillsAndFakeFails), remoteError = Some(remoteError) map ErrorExt.generateFrom)
    for (ext <- data1.remoteError) context.system.eventStream publish HCSuspended(remoteNodeId, data.commitments.lastCrossSignedState.isHost, isLocal = false, ext.description)
    errorState StoringAndUsing data1
  } else stay

  def processBlockCount(errorState: FsmStateExt, blockCount: Long, data: HC_DATA_ESTABLISHED): HostedFsmState = {
    lazy val preimageMap = data.commitments.pendingOutgoingFulfills.map(fulfill => Crypto.sha256(fulfill.paymentPreimage) -> fulfill).toMap
    val almostTimedOutIncomingHtlcs = data.almostTimedOutIncomingHtlcs(blockCount, fulfillSafety = cfg.vals.hcParams.cltvDeltaBlocks / 4 * 3)
    val timedoutOutgoingAdds = data.timedOutOutgoingHtlcs(blockCount)

    for {
      theirAdd <- almostTimedOutIncomingHtlcs
      fulfill <- preimageMap.get(theirAdd.paymentHash)
      msg = AlmostTimedoutIncomingHtlc(theirAdd, fulfill, remoteNodeId, blockCount)
      if !data.commitments.lastCrossSignedState.isHost
    } context.system.eventStream publish msg

    if (timedoutOutgoingAdds.nonEmpty) {
      failTimedoutOutgoing(localAdds = timedoutOutgoingAdds, data) // Catch all outgoing HTLCs, even the ones they have failed but not signed yet
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC) // Remove all their updates except fulfills, transition to error state
      val fakeFailsForOutgoingAdds = for (add <- timedoutOutgoingAdds) yield UpdateFailHtlc(channelId, add.id, reason = ByteVector.empty) // Fake-fail timedout outgoing HTLCs
      val commits1 = data1.commitments.copy(nextRemoteUpdates = data1.commitments.nextRemoteUpdates ++ fakeFailsForOutgoingAdds) // Our timed out HTLCs are no longer seen as pending
      errorState StoringAndUsing data1.copy(commitments = commits1) SendingHasChannelId error
    } else stay
  }

  def processExternalFulfill(errorState: FsmStateExt, cmd: HC_CMD_EXTERNAL_FULFILL, data: HC_DATA_ESTABLISHED): HostedFsmState = {
    val fulfill = UpdateFulfillHtlc(channelId, cmd.htlcId, cmd.paymentPreimage)
    val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_HTLC_EXTERNAL_FULFILL)
    errorState StoringAndUsing data1 replying CMDResSuccess(cmd) Receiving fulfill SendingHasChannelId error
  }

  def processResizeProposal(errorState: FsmStateExt, resize: ResizeChannel, data: HC_DATA_ESTABLISHED): HostedFsmState = {
    val isSignatureFine = resize.verifyClientSig(remoteNodeId)

    if (!data.isResizeSupported) {
      log.info(s"PLGN PHC, resize check fail, not supported, peer=$remoteNodeId")
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_INVALID_RESIZE)
      errorState StoringAndUsing data1 SendingHasChannelId error
    } else if (resize.newCapacity < data.commitments.capacity) {
      log.info(s"PLGN PHC, resize check fail, new capacity is less than current one, peer=$remoteNodeId")
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_INVALID_RESIZE)
      errorState StoringAndUsing data1 SendingHasChannelId error
    } else if (cfg.vals.phcConfig.maxCapacity < resize.newCapacity) {
      log.info(s"PLGN PHC, resize check fail, new capacity is more than max allowed one, peer=$remoteNodeId")
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_INVALID_RESIZE)
      errorState StoringAndUsing data1 SendingHasChannelId error
    } else if (!isSignatureFine) {
      log.info(s"PLGN PHC, resize signature check fail, peer=$remoteNodeId")
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_INVALID_RESIZE)
      errorState StoringAndUsing data1 SendingHasChannelId error
    } else {
      log.info(s"PLGN PHC, channel resize successfully accepted, peer=$remoteNodeId")
      stay StoringAndUsing data.copy(resizeProposal = Some(resize), overrideProposal = None)
    }
  }

  def manageUpdates(data: HC_DATA_ESTABLISHED): Unit = {
    if (cfg.vals.hcParams lastUpdateDiffers data.channelUpdate) {
      log.info(s"PLGN PHC, re-broadcasting, params differ, peer=$remoteNodeId")
      self ! HostedChannel.SendAnnouncements(force = false)
    } else if (data.shouldBroadcastUpdateRightAway) {
      log.info(s"PLGN PHC, re-broadcasting, last was long ago, peer=$remoteNodeId")
      self ! HostedChannel.SendAnnouncements(force = false)
    }
  }

  def attemptStateUpdate(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED): HostedFsmState = {
    val lcss1 = data.commitments.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(kit.nodeParams.privateKey)
    val commits1 = data.commitments.copy(lastCrossSignedState = lcss1, localSpec = data.commitments.nextLocalSpec, nextLocalUpdates = Nil, nextRemoteUpdates = Nil)
    val isRemoteSigOk = lcss1.verifyRemoteSig(remoteNodeId)
    val isBlockDayWrong = isBlockDayOutOfSync(remoteSU)

    if (isBlockDayWrong) {
      val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
      goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
    } else if (remoteSU.remoteUpdates < lcss1.localUpdates) {
      // Persist unsigned remote updates to use them on re-sync
      stay StoringAndUsing data Receiving CMD_SIGN(None)
    } else if (!isRemoteSigOk) {
      data.resizeProposal.map(data.withResize) match {
        case Some(data1) => attemptStateUpdate(remoteSU, data1)
        case None =>
          val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      }
    } else {
      val commitments1 = clearOrigin(commits1, data.commitments)
      context.system.eventStream publish AvailableBalanceChanged(self, channelId, shortChannelId, commitments = commitments1)
      stay StoringAndUsing data.copy(commitments = commitments1) RelayingRemoteUpdates data.commitments SendingHosted commits1.lastCrossSignedState.stateUpdate
    }
  }

  type CommitsAndHtlcMessage = (HostedCommitments, HasChannelId)
  private def resolveSettlementCommand(result: Either[ChannelException, CommitsAndHtlcMessage], data: HC_DATA_ESTABLISHED, cmd: HtlcSettlementCommand, commit: Boolean) = result match {
    case Right((commits1, message)) if commit => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId message Receiving CMD_SIGN(None)
    case Right((commits1, message)) => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId message
    case Left(cause) =>
      PendingCommandsDb.ackSettlementCommand(kit.nodeParams.db.pendingCommands, channelId, cmd)
      replyToCommand(RES_FAILURE(cmd, cause), cmd)
      stay
  }

  private def replyToCommand(reply: CommandResponse[Command], cmd: Command): Unit = cmd match {
    case cmd1: HasReplyToCommand => if (cmd1.replyTo == ActorRef.noSender) sender ! reply else cmd1.replyTo ! reply
    case cmd1: HasOptionalReplyToCommand => cmd1.replyTo_opt.foreach(_ ! reply)
  }
}
