package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app._
import fr.acinq.eclair.channel._
import scala.concurrent.duration._

import fr.acinq.eclair.db.PendingRelayDb.{ackCommand, getPendingFailsAndFulfills}
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import fr.acinq.hc.app.Tools.{DuplicateHandler, DuplicateShortId}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.io.{Peer, PeerDisconnected}
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, FSM}

import fr.acinq.hc.app.wire.Codecs.LocalOrRemoteUpdateWithChannelId
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.hc.app.dbo.HostedChannelsDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.hc.app.network.PHC
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import scodec.bits.ByteVector
import fr.acinq.eclair.wire


object HostedChannel {
  case object SendAnnouncements { val label = "SendAnnouncements" }

  case object RemoteUpdateTimeout { val label = "RemoteUpdateTimeout" }
}

class HostedChannel(kit: Kit, connections: mutable.Map[PublicKey, PeerConnectedWrap], remoteNodeId: PublicKey, channelsDb: HostedChannelsDb,
                    hostedSync: ActorRef, vals: Vals) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  lazy val channelId: ByteVector32 = Tools.hostedChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  lazy val shortChannelId: ShortChannelId = Tools.hostedShortChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  lazy val initParams: HCParams = vals.hcOverrideMap.get(remoteNodeId).map(_.params).getOrElse(vals.hcDefaultParams)

  startTimerWithFixedDelay(HostedChannel.SendAnnouncements.label, HostedChannel.SendAnnouncements, PHC.tickAnnounceThreshold)

  context.system.eventStream.subscribe(channel = classOf[CurrentBlockCount], subscriber = self)

  startWith(OFFLINE, HC_NOTHING)

  when(OFFLINE) {
    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) => stay using data

    case Event(Worker.HCPeerConnected, HC_NOTHING) => goto(SYNCING)

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) if data.commitments.isHost =>
      if (data.refundCompleteInfo.isDefined) goto(CLOSED)
      else if (data.errorExt.isDefined) goto(CLOSED)
      else goto(SYNCING)

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) =>
      // Client is the one who sends either an Error or InvokeHostedChannel on reconnect
      if (data.localError.isDefined) goto(CLOSED) SendingHasChannelId data.localError.get.error
      else if (data.remoteError.isDefined) goto(CLOSED) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER)
      else goto(SYNCING) SendingHosted InvokeHostedChannel(kit.nodeParams.chainHash, data.commitments.lastCrossSignedState.refundScriptPubKey)

    case Event(Worker.TickRemoveIdleChannels, HC_NOTHING) => stop(FSM.Normal)

    case Event(Worker.TickRemoveIdleChannels, data: HC_DATA_ESTABLISHED) =>
      // Client channels are never idle, instead they always try to reconnect on becoming OFFLINE
      // Host channel with outgoing HTLCs is kept because peer may technically send fulfill while OFFLINE
      // It's OK to remove a channel with pending incoming HTLCs, but it must be treated as hot on restart
      val canStop = data.commitments.isHost && data.timedOutOutgoingHtlcs(Long.MaxValue).isEmpty
      if (canStop) stop(FSM.Normal) else stay
  }

  when(SYNCING, stateTimeout = 5.minutes) {
    case Event(StateTimeout, _: HC_DATA_ESTABLISHED) => stay.Disconnecting

    case Event(StateTimeout, _) => stop(FSM.Normal)

    case Event(HC_CMD_LOCAL_INVOKE(_, scriptPubKey, secret), HC_NOTHING) =>
      val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, scriptPubKey, secret)
      stay using HC_DATA_CLIENT_WAIT_HOST_INIT(scriptPubKey) SendingHosted invokeMsg

    case Event(remoteInvoke: InvokeHostedChannel, HC_NOTHING) =>
      if (kit.nodeParams.chainHash != remoteInvoke.chainHash) stay SendingHasChannelId makeError(InvalidChainHash(channelId, kit.nodeParams.chainHash, remoteInvoke.chainHash).getMessage)
      else if (Helpers.Closing isValidFinalScriptPubkey remoteInvoke.refundScriptPubKey) stay using HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(remoteInvoke) SendingHosted initParams.initMsg
      else stay SendingHasChannelId makeError(InvalidFinalScript(channelId).getMessage)

    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) if data.commitments.isHost => stay SendingHosted data.commitments.lastCrossSignedState

    case Event(hostInit: InitHostedChannel, data: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      if (hostInit.liabilityDeadlineBlockdays < vals.hcDefaultParams.liabilityDeadlineBlockdays) stop(FSM.Normal) SendingHasChannelId makeError("Proposed liability deadline is too low")
      else if (hostInit.minimalOnchainRefundAmountSatoshis > vals.hcDefaultParams.initMsg.minimalOnchainRefundAmountSatoshis) stop(FSM.Normal) SendingHasChannelId makeError("Proposed minimal refund is too high")
      else if (hostInit.initialClientBalanceMsat > vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) SendingHasChannelId makeError("Proposed init balance for us is larger than capacity")
      else if (hostInit.channelCapacityMsat < vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) SendingHasChannelId makeError("Proposed channel capacity is too low")
      else {
        val localUnsignedLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = hostInit, blockDay = currentBlockDay,
          localBalanceMsat = hostInit.initialClientBalanceMsat, remoteBalanceMsat = hostInit.channelCapacityMsat - hostInit.initialClientBalanceMsat,
          localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
        val commitments: HostedCommitments = restoreEmptyData(localUnsignedLCSS.withLocalSigOfRemote(kit.nodeParams.privateKey), isHost = false).commitments
        stay using HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commitments) SendingHosted commitments.lastCrossSignedState.stateUpdate(isTerminal = true)
      }

    case Event(clientSU: StateUpdate, wait: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val handle = new DuplicateHandler[HC_DATA_ESTABLISHED] { def insert(data: HC_DATA_ESTABLISHED): Boolean = channelsDb addNewChannel data }
      val fullySignedLCSS = LastCrossSignedState(wait.invoke.refundScriptPubKey, initHostedChannel = initParams.initMsg, blockDay = clientSU.blockDay,
        localBalanceMsat = initParams.initMsg.channelCapacityMsat, remoteBalanceMsat = MilliSatoshi(0L), localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil,
        outgoingHtlcs = Nil, remoteSigOfLocal = clientSU.localSigOfRemoteLCSS, localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

      val data = restoreEmptyData(fullySignedLCSS, isHost = true)
      val isLocalSigOk = fullySignedLCSS.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(clientSU)

      if (isBlockDayWrong) stop(FSM.Normal) SendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, clientSU.blockDay).getMessage)
      else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else handle.execute(data) match {
        case Failure(DuplicateShortId) =>
          log.info(s"PLGN PHC, DuplicateShortId when storing new HC, peer=$remoteNodeId")
          stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)

        case Success(true) =>
          val localSU = fullySignedLCSS.stateUpdate(isTerminal = true)
          log.info(s"PLGN PHC, stored new HC with peer=$remoteNodeId, shortId=$shortChannelId")
          goto(NORMAL) StoringAndUsing data StoringSecret wait.invoke.finalSecret SendingHosted localSU

        case _ =>
          log.info(s"PLGN PHC, database error when storing new HC, peer=$remoteNodeId")
          stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
      }

    case Event(hostSU: StateUpdate, data: HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commitments.lastCrossSignedState.copy(remoteSigOfLocal = hostSU.localSigOfRemoteLCSS)
      val isLocalSigOk = fullySignedLCSS.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(hostSU)

      if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else if (isBlockDayWrong) stop(FSM.Normal) SendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, hostSU.blockDay).getMessage)
      else if (data.commitments.lastCrossSignedState.remoteUpdates != hostSU.localUpdates) stop(FSM.Normal) SendingHasChannelId makeError("Proposed remote/local update number mismatch")
      else if (data.commitments.lastCrossSignedState.localUpdates != hostSU.remoteUpdates) stop(FSM.Normal) SendingHasChannelId makeError("Proposed local/remote update number mismatch")
      else goto(NORMAL) StoringAndUsing restoreEmptyData(fullySignedLCSS, isHost = false)

    // MISSING CHANNEL

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_CLIENT_WAIT_HOST_INIT) => restoreMissingChannel(remoteLCSS, isHost = false)

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) => restoreMissingChannel(remoteLCSS, isHost = true)

    case Event(_: InitHostedChannel, data: HC_DATA_ESTABLISHED) if !data.commitments.isHost => stay SendingHosted data.commitments.lastCrossSignedState

    // NORMAL PATHWAY

    case Event(remoteLCSS: LastCrossSignedState, data: HC_DATA_ESTABLISHED) =>
      val weAreAhead = data.commitments.lastCrossSignedState.isAhead(remoteLCSS)
      val weAreEven = data.commitments.lastCrossSignedState.isEven(remoteLCSS)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)

      if (!isRemoteSigOk) {
        val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      } else if (!isLocalSigOk) {
        val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
        goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      } else if (weAreAhead || weAreEven) {
        // Resend our local pending updates (adds, fails and fulfills) but retain our current cross-signed state since we are ahead or even
        val commits1 = syncAndResend(data.commitments, data.commitments.futureUpdates, data.commitments.lastCrossSignedState, data.commitments.localSpec)
        goto(NORMAL) using data.copy(commitments = commits1)
      } else {
        data.commitments.findState(remoteLCSS).headOption map { commits1 =>
          val leftOvers = data.commitments.futureUpdates.diff(commits1.futureUpdates)
          // They have one of our future states, we also may have local pending updates (adds, fails and fulfills)
          // this may happen if we send a message followed by StateUpdate, then disconnect (they have our future state)
          val commits2 = syncAndResend(commits1, leftOvers, remoteLCSS.reverse, commits1.nextLocalSpec)
          goto(NORMAL) StoringAndUsing data.copy(commitments = commits2)
        } getOrElse {
          // We are disconnected from our own future state, fail and resolve manually
          val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_STATE_DISCONNECTED)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
        }
      }
  }

  when(NORMAL) {
    case Event(HostedChannel.RemoteUpdateTimeout, _: HC_DATA_ESTABLISHED) =>
      log.info(s"PLGN PHC, remote update timeout, peer=$remoteNodeId")
      stay.Disconnecting

    // PHC announcements

    case Event(HostedChannel.SendAnnouncements, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val lastUpdateTooLongAgo = data.localChannelUpdate.timestamp < System.currentTimeMillis - PHC.reAnnounceThreshold
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1: HC_DATA_ESTABLISHED = data.copy(localChannelUpdate = update1)
      context.system.eventStream publish makeLocalUpdateEvent(data1)

      data1.channelAnnouncement match {
        case None => stay SendingHosted Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = true)
        case Some(announce) if lastUpdateTooLongAgo => stay StoringAndUsing data1 Announcing announce Announcing data1.localChannelUpdate
        case _ => stay StoringAndUsing data1 Announcing data1.localChannelUpdate
      }

    case Event(remoteSig: AnnouncementSignature, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val localSig = Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = false)
      val announce = Tools.makePHCAnnouncement(kit.nodeParams, localSig, remoteSig, shortChannelId, remoteNodeId)
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1 = data.copy(channelAnnouncement = Some(announce), localChannelUpdate = update1)
      val isSigOK = Announcements.checkSigs(announce)

      if (isSigOK && remoteSig.wantsReply) {
        log.info(s"PLGN PHC, announcing PHC and sending sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 SendingHosted localSig Announcing announce Announcing data1.localChannelUpdate
      } else if (isSigOK) {
        log.info(s"PLGN PHC, announcing PHC without sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 Announcing announce Announcing data1.localChannelUpdate
      } else {
        log.info(s"PLGN PHC, announce sig check failed, peer=$remoteNodeId")
        stay
      }

    case Event(cmd: HC_CMD_PUBLIC, data: HC_DATA_ESTABLISHED) =>
      val commitments1 = data.commitments.copy(announceChannel = true)
      val data1 = data.copy(channelAnnouncement = None, commitments = commitments1)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd) Receiving HostedChannel.SendAnnouncements

    case Event(cmd: HC_CMD_PRIVATE, data: HC_DATA_ESTABLISHED) =>
      val commitments1 = data.commitments.copy(announceChannel = false)
      val data1 = data.copy(channelAnnouncement = None, commitments = commitments1)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd)

    // Payments

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendAdd(cmd, kit.nodeParams.currentBlockHeight) match {
        case Right((commits1, add)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) AckingAddSuccess cmd SendingHasChannelId add Receiving CMD_SIGN(None)
        case Right((commits1, add)) => stay StoringAndUsing data.copy(commitments = commits1) AckingAddSuccess cmd SendingHasChannelId add
        case Left(cause) => ackAddFail(cmd, cause, data.localChannelUpdate)
      }

    // Peer adding and failing HTLCs is only accepted in NORMAL

    case Event(add: wire.UpdateAddHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveAdd(add) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) =>
          val (data1, error) = withLocalError(data, cause.getMessage)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      }

    case Event(fail: wire.UpdateFailHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveFail(fail) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) =>
          val (data1, error) = withLocalError(data, cause.getMessage)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      }

    case Event(fail: wire.UpdateFailMalformedHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveFailMalformed(fail) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) =>
          val (data1, error) = withLocalError(data, cause.getMessage)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      }

    case Event(_: CMD_SIGN, data: HC_DATA_ESTABLISHED) if data.commitments.futureUpdates.nonEmpty =>
      // Peer may stay online and send other messages without sending of remote StateUpdate due to a bug, make sure we disconnect then
      startSingleTimer(HostedChannel.RemoteUpdateTimeout.label, HostedChannel.RemoteUpdateTimeout, kit.nodeParams.revocationTimeout)
      val localLCSS = data.commitments.nextLocalUnsignedLCSS(currentBlockDay).withLocalSigOfRemote(kit.nodeParams.privateKey)
      stay SendingHosted localLCSS.stateUpdate(isTerminal = false)

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) if remoteSU.localSigOfRemoteLCSS == data.commitments.lastCrossSignedState.remoteSigOfLocal =>
      // Do nothing if we get a duplicate for new cross-signed state, this can often happen normally (in particular on reconnect sync)
      cancelTimer(HostedChannel.RemoteUpdateTimeout.label)
      stay

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) =>
      cancelTimer(HostedChannel.RemoteUpdateTimeout.label)

      val lcss1 = data.commitments.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(kit.nodeParams.privateKey)
      val commits1 = data.commitments.copy(lastCrossSignedState = lcss1, localSpec = data.commitments.nextLocalSpec, futureUpdates = Nil)
      val isRemoteSigOk = lcss1.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(remoteSU)

      if (isBlockDayWrong) {
        // Irregardless of what they send a blockday must always be correct
        val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
        goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      } else if (remoteSU.remoteUpdates < lcss1.localUpdates) {
        // Checking before sig because it may not match with many updates in-flight
        stay SendingHosted commits1.lastCrossSignedState.stateUpdate(isTerminal = false)
      } else if (!isRemoteSigOk) {
        val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
        goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
      } else if (!remoteSU.isTerminal) {
        // They have sent an update for our previous state, resent current one again
        stay SendingHosted commits1.lastCrossSignedState.stateUpdate(isTerminal = true)
      } else {
        data.commitments.nextRemoteUpdates.collect {
          case malformedFail: wire.UpdateFailMalformedHtlc =>
            val origin = data.commitments.originChannels(malformedFail.id)
            val outgoing = data.commitments.localSpec.findOutgoingHtlcById(malformedFail.id).get
            kit.relayer ! RES_ADD_SETTLED(origin, outgoing.add, HtlcResult RemoteFailMalformed malformedFail)

          case fail: wire.UpdateFailHtlc =>
            val origin = data.commitments.originChannels(fail.id)
            val outgoing = data.commitments.localSpec.findOutgoingHtlcById(fail.id).get
            kit.relayer ! RES_ADD_SETTLED(origin, outgoing.add, HtlcResult RemoteFail fail)

          case add: wire.UpdateAddHtlc =>
            kit.relayer ! Relayer.RelayForward(add)
        }

        val completedOutgoingHtlcs = data.commitments.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id) -- commits1.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id)
        val refundingResetData = data.copy(commitments = commits1.copy(originChannels = commits1.originChannels -- completedOutgoingHtlcs), refundPendingInfo = None)
        context.system.eventStream publish AvailableBalanceChanged(self, channelId, shortChannelId, refundingResetData.commitments)
        stay StoringAndUsing refundingResetData SendingHosted commits1.lastCrossSignedState.stateUpdate(isTerminal = true)
      }
  }

  when(CLOSED) {
    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) if data.commitments.isHost =>
      if (data.localError.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId data.localError.get.error
      else if (data.remoteError.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER)
      else if (data.refundCompleteInfo.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId makeError(data.refundCompleteInfo.get)
      else stay

    // OVERRIDING

    case Event(remoteSO: StateOverride, data: HC_DATA_ESTABLISHED) if !data.commitments.isHost =>
      val data1 = data.copy(overrideProposal = Some(remoteSO), refundPendingInfo = None)
      stay StoringAndUsing data1

    case Event(cmd: HC_CMD_OVERRIDE_ACCEPT, data: HC_DATA_ESTABLISHED) =>
      if (data.errorExt.isEmpty) stay replying FSM.Failure("Overriding declined: channel is in normal state")
      else if (data.commitments.isHost) stay replying FSM.Failure("Overriding declined: only client side can accept override")
      else if (data.overrideProposal.isEmpty) stay replying FSM.Failure("Overriding declined: no override proposal from host is found")
      else {
        val remoteSO: StateOverride = data.overrideProposal.get
        val newLocalBalance = data.commitments.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat
        val completeLocalLCSS = data.commitments.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil, localBalanceMsat = newLocalBalance,
          remoteBalanceMsat = remoteSO.localBalanceMsat, localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates, blockDay = remoteSO.blockDay,
          remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS).withLocalSigOfRemote(kit.nodeParams.privateKey)
        val isRemoteSigOk = completeLocalLCSS.verifyRemoteSig(remoteNodeId)

        if (remoteSO.localUpdates < data.commitments.lastCrossSignedState.remoteUpdates) stay replying FSM.Failure("Overridden local update number is less than remote")
        else if (remoteSO.remoteUpdates < data.commitments.lastCrossSignedState.localUpdates) stay replying FSM.Failure("Overridden remote update number is less than local")
        else if (remoteSO.blockDay < data.commitments.lastCrossSignedState.blockDay) stay replying FSM.Failure("Overridden remote blockday is less than local")
        else if (newLocalBalance > data.commitments.capacity) stay replying FSM.Failure("Overriding declined: new local balance exceeds capacity")
        else if (newLocalBalance < 0L.msat) stay replying FSM.Failure("Overriding declined: new local balance is less than zero")
        else if (!isRemoteSigOk) stay replying FSM.Failure("Remote override signature is wrong")
        else {
          val localSU = completeLocalLCSS.stateUpdate(isTerminal = true)
          val data1 = restoreEmptyData(completeLocalLCSS, isHost = false)
          failTimedoutOutgoing(localAdds = data.timedOutOutgoingHtlcs(Long.MaxValue), data)
          goto(NORMAL) StoringAndUsing data1 replying CMDResSuccess(cmd) SendingHosted localSU
        }
      }

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) if data.commitments.isHost =>
      data.overrideProposal.map { case StateOverride(blockDay, localBalanceMsat, localUpdates, remoteUpdates, _) =>
        val overridingLocallySignedLCSS = makeOverridingLocallySignedLCSS(data.commitments, localBalanceMsat, localUpdates, remoteUpdates, blockDay)
        val completeLocallySignedLCSS = overridingLocallySignedLCSS.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val isRemoteSigOk = completeLocallySignedLCSS.verifyRemoteSig(remoteNodeId)

        if (remoteSU.blockDay != blockDay) stay SendingHasChannelId makeError("Override blockday is not acceptable")
        else if (remoteSU.remoteUpdates != localUpdates) stay SendingHasChannelId makeError("Override remote update number is wrong")
        else if (remoteSU.localUpdates != remoteUpdates) stay SendingHasChannelId makeError("Override local update number is wrong")
        else if (!isRemoteSigOk) stay SendingHasChannelId makeError("Override signature is wrong")
        else {
          val data1 = restoreEmptyData(completeLocallySignedLCSS, isHost = true)
          failTimedoutOutgoing(localAdds = data.timedOutOutgoingHtlcs(Long.MaxValue), data)
          goto(NORMAL) StoringAndUsing data1
        }
      } getOrElse {
        stay
      }
  }

  whenUnhandled {
    case Event(_: PeerDisconnected, _: HC_DATA_ESTABLISHED) =>
      // This channel has been persisted, keep data online
      goto(OFFLINE)

    case Event(_: PeerDisconnected, _) =>
      // Channel is not persisted so it was in establishing state
      log.info(s"PLGN PHC, disconnect while establishing HC")
      stop(FSM.Normal)

    case Event(remoteError: wire.Error, data: HC_DATA_ESTABLISHED) =>
      if (data.remoteError.isEmpty) {
        val errorExtOpt = Some(remoteError).map(ErrorExt.generateFrom)
        goto(CLOSED) StoringAndUsing data.copy(remoteError = errorExtOpt)
      } else {
        stay
      }

    case Event(remoteError: wire.Error, _) =>
      val description = ErrorExt.extractDescription(remoteError)
      log.info(s"PLGN PHC, HC establish remote error, error=$description")
      stop(FSM.Normal)

    case Event(cmd: CurrentBlockCount, data: HC_DATA_ESTABLISHED) =>
      val failedIds: Set[UpdateAddHtlc] = data.timedOutOutgoingHtlcs(cmd.blockCount)
      val fakeFailsForOutgoingAdds = for (add <- failedIds) yield UpdateFailHtlc(channelId, add.id, ByteVector32.Zeroes)
      val commits1 = fakeFailsForOutgoingAdds.map(Right.apply).foldLeft(data.commitments)(_ addProposal _)

      failedIds.isEmpty match {
        case false if CLOSED != stateName =>
          failTimedoutOutgoing(localAdds = failedIds, data)
          val (data1, error) = withLocalError(data, ErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
          goto(CLOSED) StoringAndUsing data1.copy(commitments = commits1) SendingHasChannelId error
        case false => stay StoringAndUsing data.copy(commitments = commits1)
        case true => stay
      }

    case Event(fulfill: UpdateFulfillHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveFulfill(fulfill) match {
        case Success((commits1, origin, htlc)) =>
          val result = HtlcResult.RemoteFulfill(fulfill)
          kit.relayer ! RES_ADD_SETTLED(origin, htlc, result)
          stay StoringAndUsing data.copy(commitments = commits1)
        case Failure(cause) if CLOSED != stateName =>
          val (data1, error) = withLocalError(data, cause.getMessage)
          goto(CLOSED) StoringAndUsing data1 SendingHasChannelId error
        case _ => stay
      }

    case Event(cmd: CMD_FULFILL_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendFulfill(cmd) match {
        case Success((commits1, fulfill)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fulfill Receiving CMD_SIGN(None)
        case Success((commits1, fulfill)) => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fulfill
        case Failure(cause) => stay.AckingFail(cause, cmd)
      }

    case Event(cmd: CMD_FAIL_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendFail(cmd, kit.nodeParams.privateKey) match {
        case Success((commits1, fail)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fail Receiving CMD_SIGN(None)
        case Success((commits1, fail)) => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fail
        case Failure(cause) => stay.AckingFail(cause, cmd)
      }

    case Event(cmd: CMD_FAIL_MALFORMED_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendFailMalformed(cmd) match {
        case Success((commits1, fail)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fail Receiving CMD_SIGN(None)
        case Success((commits1, fail)) => stay StoringAndUsing data.copy(commitments = commits1) AckingSuccess cmd SendingHasChannelId fail
        case Failure(cause) => stay.AckingFail(cause, cmd)
      }

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      ackAddFail(cmd, ChannelUnavailable(channelId), data.localChannelUpdate)
      val isUpdateEnabled = Announcements.isEnabled(data.localChannelUpdate.channelFlags)
      log.info(s"PLGN PHC, rejecting htlc request in state=$stateName, peer=$remoteNodeId")

      if (data.commitments.announceChannel && isUpdateEnabled) {
        // In order to reduce gossip spam, we don't disable the channel right away when disconnected
        // we will only emit a new ChannelUpdate with the disable flag set if someone tries to use it
        val disableUpdate = makeChannelUpdate(data.commitments.lastCrossSignedState, enable = false)
        stay StoringAndUsing data.copy(localChannelUpdate = disableUpdate) Announcing disableUpdate
      } else {
        stay
      }

    // Refunds

    case Event(cmd: HC_CMD_INIT_PENDING_REFUND, data: HC_DATA_ESTABLISHED) =>
      val refundPending: RefundPending = RefundPending(System.currentTimeMillis)
      val data1 = data.copy(refundPendingInfo = Some(refundPending), overrideProposal = None)
      stay StoringAndUsing data1 replying CMDResSuccess(cmd)

    case Event(cmd: HC_CMD_FINALIZE_REFUND, data: HC_DATA_ESTABLISHED) =>
      val liabilityBlockdays = data.commitments.lastCrossSignedState.initHostedChannel.liabilityDeadlineBlockdays
      val enoughBlocksPassed = data.commitments.lastCrossSignedState.blockDay + liabilityBlockdays < currentBlockDay
      if (enoughBlocksPassed) {
        val data1 = data.copy(refundCompleteInfo = Some(cmd.info), refundPendingInfo = None)
        log.info(s"PLGN PHC, finalized refund for HC with info=${cmd.info}, peer=$remoteNodeId")
        goto(CLOSED) StoringAndUsing data1 replying CMDResSuccess(cmd)
      } else {
        val lastSignedDay = data.commitments.lastCrossSignedState.blockDay
        stay replying FSM.Failure(s"Not enough days passed since=$lastSignedDay")
      }

    // Scheduling override

    case Event(cmd: HC_CMD_OVERRIDE_PROPOSE, data: HC_DATA_ESTABLISHED) =>
      if (data.errorExt.isEmpty) stay replying FSM.Failure("Overriding declined: channel is in normal state")
      else if (!data.commitments.isHost) stay replying FSM.Failure("Overriding declined: only host side can initiate override")
      else if (data.refundCompleteInfo.isDefined) stay replying FSM.Failure("Overriding declined: target channel has been refunded")
      else if (cmd.newLocalBalance > data.commitments.capacity) stay replying FSM.Failure("Overriding declined: new local balance exceeds capacity")
      else if (cmd.newLocalBalance < 0L.msat) stay replying FSM.Failure("Overriding declined: new local balance is less than zero")
      else {
        log.info(s"PLGN PHC, scheduling override proposal for peer=$remoteNodeId")
        val newLocalUpdates = data.commitments.lastCrossSignedState.localUpdates + data.commitments.nextLocalUpdates.size + 1
        val newRemoteUpdates = data.commitments.lastCrossSignedState.remoteUpdates + data.commitments.nextRemoteUpdates.size + 1
        val overridingLocallySignedLCSS = makeOverridingLocallySignedLCSS(data.commitments, cmd.newLocalBalance, newLocalUpdates, newRemoteUpdates, currentBlockDay)
        val localSO = StateOverride(overridingLocallySignedLCSS.blockDay, overridingLocallySignedLCSS.localBalanceMsat, overridingLocallySignedLCSS.localUpdates,
          overridingLocallySignedLCSS.remoteUpdates, overridingLocallySignedLCSS.localSigOfRemote)
        val data1 = data.copy(overrideProposal = Some(localSO), refundPendingInfo = None)
        stay StoringAndUsing data1 replying CMDResSuccess(cmd) SendingHosted localSO
      }

    // Misc

    case Event(HC_CMD_GET_INFO, data: HC_DATA_ESTABLISHED) => stay replying CMDResponseInfo(stateName, data, data.commitments.nextLocalSpec)

    case Event(cmd: HC_CMD_EXTERNAL_FULFILL, _: HC_DATA_ESTABLISHED) => stay replying CMDResSuccess(cmd) Receiving UpdateFulfillHtlc(channelId, cmd.htlcId, cmd.paymentPreimage)

    case Event(cmd: HasRemoteNodeIdHostedCommand, data) => stay replying FSM.Failure(s"Can't process, data=${data.getClass.getSimpleName}, state=$stateName")
  }

  onTransition {
    case state -> nextState =>
      Tuple3(state, nextState, nextStateData) match {
        case Tuple3(SYNCING | CLOSED, NORMAL, d1: HC_DATA_ESTABLISHED) =>
          context.system.eventStream publish ChannelRestored(self, channelId, peer = null, remoteNodeId, isFunder = false, d1.commitments)
          context.system.eventStream publish ChannelIdAssigned(self, remoteNodeId, temporaryChannelId = ByteVector32.Zeroes, channelId)
          context.system.eventStream publish ShortChannelIdAssigned(self, channelId, shortChannelId, previousShortChannelId = None)
          context.system.eventStream publish makeLocalUpdateEvent(d1)
        case Tuple3(NORMAL, OFFLINE | CLOSED, _) =>
          context.system.eventStream publish LocalChannelDown(self, channelId, shortChannelId, remoteNodeId)
        case _ =>
      }

      Tuple3(state, nextState, nextStateData) match {
        case Tuple3(OFFLINE | SYNCING, NORMAL | CLOSED, d1: HC_DATA_ESTABLISHED) if d1.pendingHtlcs.nonEmpty =>
          val pending = getPendingFailsAndFulfills(kit.nodeParams.db.pendingRelay, channelId)(log)
          for (failOrFulfillCommand <- pending) self ! failOrFulfillCommand
          if (pending.nonEmpty) self ! CMD_SIGN(None)
        case _ =>
      }

      Tuple4(connections.get(remoteNodeId), state, nextState, nextStateData) match {
        case Tuple4(Some(connection), OFFLINE | SYNCING, NORMAL | CLOSED, d1: HC_DATA_ESTABLISHED) =>
          context.system.eventStream publish ChannelStateChanged(self, channelId, peer = null, remoteNodeId, state, nextState, d1.commitmentsOpt)
          d1.refundPendingInfo.foreach(connection.sendHostedChannelMsg)
          d1.overrideProposal.foreach(connection.sendHostedChannelMsg)
        case _ =>
      }

      Tuple4(connections.get(remoteNodeId), state, nextState, nextStateData) match {
        case Tuple4(Some(connection), OFFLINE | SYNCING, CLOSED, d1: HC_DATA_ESTABLISHED) =>
          // We may get fulfills for peer payments while offline when channel is in error state, resend them
          val fulfills = d1.commitments.nextLocalUpdates.collect { case fulfill: UpdateFulfillHtlc => fulfill }
          fulfills.foreach(connection.sendHasChannelIdMsg)
        case _ =>
      }
  }

  onTransition {
    case (SYNCING | CLOSED) -> NORMAL =>
      (connections.get(remoteNodeId), stateData, nextStateData) match {
        case (_, _, d1: HC_DATA_ESTABLISHED) if d1.commitments.announceChannel && !Announcements.isEnabled(d1.localChannelUpdate.channelFlags) => self ! HostedChannel.SendAnnouncements
        case (Some(conn), d0: HC_DATA_ESTABLISHED, d1: HC_DATA_ESTABLISHED) if !d1.commitments.announceChannel && d0.localChannelUpdate != d1.localChannelUpdate => conn sendRoutingMsg d1.localChannelUpdate
        case (Some(conn), _, d1: HC_DATA_ESTABLISHED) if !d1.commitments.announceChannel => conn sendRoutingMsg d1.localChannelUpdate
        case _ =>
      }
  }

  type ChanState = fr.acinq.eclair.channel.State
  type HostedFsmState = FSM.State[ChanState, HostedData]

  implicit class FsmStateExt(state: HostedFsmState) {
    def SendingHasChannelId(messages: wire.HasChannelId *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(messages foreach _.sendHasChannelIdMsg)
      state
    }

    def SendingHosted(messages: HostedChannelMessage *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(messages foreach _.sendHostedChannelMsg)
      state
    }

    def Disconnecting: HostedFsmState = {
      val message = Peer.Disconnect(remoteNodeId)
      connections.get(remoteNodeId).foreach(_.info.peer ! message)
      state
    }

    def AckingSuccess(cmd: HtlcSettlementCommand): HostedFsmState = {
      Channel.replyToCommand(self, reply = RES_SUCCESS(cmd, channelId), cmd)
      ackCommand(kit.nodeParams.db.pendingRelay, channelId, cmd)
      state
    }

    def AckingFail(cause: Throwable, cmd: HtlcSettlementCommand): HostedFsmState = {
      Channel.replyToCommand(self, reply = RES_FAILURE(cmd, cause), cmd)
      ackCommand(kit.nodeParams.db.pendingRelay, channelId, cmd)
      state
    }

    def AckingAddSuccess(cmd: CMD_ADD_HTLC): HostedFsmState = {
      Channel.replyToCommand(self, reply = RES_SUCCESS(cmd, channelId), cmd)
      state
    }

    def Announcing(message: wire.AnnouncementMessage): HostedFsmState = {
      hostedSync ! Codecs.toUnknownAnnounceMessage(message, isGossip = true)
      state
    }

    def Receiving(message: Any): HostedFsmState = {
      self ! message
      state
    }

    def StoringAndUsing(data: HC_DATA_ESTABLISHED): HostedFsmState = {
      channelsDb.updateOrAddNewChannel(data)
      state using data
    }

    def StoringSecret(secret: ByteVector): HostedFsmState = {
      channelsDb.updateSecretById(remoteNodeId, secret)
      state
    }
  }

  initialize()

  def currentBlockDay: Long = kit.nodeParams.currentBlockHeight / 144

  def makeError(content: String): wire.Error = wire.Error(channelId, content)

  def isBlockDayOutOfSync(remoteSU: StateUpdate): Boolean = math.abs(remoteSU.blockDay - currentBlockDay) > 1

  def makeLocalUpdateEvent(data: HC_DATA_ESTABLISHED): LocalChannelUpdate = LocalChannelUpdate(self, channelId, shortChannelId, remoteNodeId, None, data.localChannelUpdate, data.commitments)

  def makeChannelUpdate(localLCSS: LastCrossSignedState, enable: Boolean): wire.ChannelUpdate =
    Announcements.makeChannelUpdate(kit.nodeParams.chainHash, kit.nodeParams.privateKey, remoteNodeId, shortChannelId, initParams.cltvDelta,
      initParams.htlcMinimumMsat.msat, initParams.feeBase, initParams.feeProportionalMillionths, localLCSS.initHostedChannel.channelCapacityMsat, enable)

  def makeOverridingLocallySignedLCSS(commits: HostedCommitments, newLocalBalance: MilliSatoshi, newLocalUpdates: Long, newRemoteUpdates: Long, overrideBlockDay: Long): LastCrossSignedState =
    commits.lastCrossSignedState.copy(localBalanceMsat = newLocalBalance, remoteBalanceMsat = commits.lastCrossSignedState.initHostedChannel.channelCapacityMsat - newLocalBalance,
      incomingHtlcs = Nil, outgoingHtlcs = Nil, localUpdates = newLocalUpdates, remoteUpdates = newRemoteUpdates, blockDay = overrideBlockDay,
      remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

  def restoreEmptyData(localLCSS: LastCrossSignedState, isHost: Boolean): HC_DATA_ESTABLISHED =
    HC_DATA_ESTABLISHED(HostedCommitments(isHost, localNodeId = kit.nodeParams.nodeId, remoteNodeId = remoteNodeId, channelId,
      CommitmentSpec(htlcs = Set.empty, FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat),
      originChannels = Map.empty, lastCrossSignedState = localLCSS, futureUpdates = Nil, announceChannel = false),
      makeChannelUpdate(localLCSS, enable = true), localError = None)

  def restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost: Boolean): HostedFsmState = {
    log.info(s"PLGN PHC, restoring missing hosted channel with peer=$remoteNodeId")
    val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
    val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
    val data1 = restoreEmptyData(remoteLCSS.reverse, isHost)

    if (!isRemoteSigOk) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG) // Their proposed signature does not match, an obvious error
    else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG) // Our own signature does not match, means we did not sign it earlier
    else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE)
    else goto(NORMAL) StoringAndUsing data1 SendingHosted data1.commitments.lastCrossSignedState
  }

  def syncAndResend(commitments: HostedCommitments, leftOvers: List[LocalOrRemoteUpdateWithChannelId], lcss: LastCrossSignedState, spec: CommitmentSpec): HostedCommitments = {
    val commits1 = commitments.copy(futureUpdates = leftOvers.filter(_.isLeft) /* remove remote updates, retain local ones */, lastCrossSignedState = lcss, localSpec = spec)
    stay.SendingHosted(commits1.lastCrossSignedState).SendingHasChannelId(commits1.nextLocalUpdates:_*)
    if (commits1.nextLocalUpdates.nonEmpty) self ! CMD_SIGN(None)
    commits1
  }

  def withLocalError(data: HC_DATA_ESTABLISHED, errorCode: String): (HC_DATA_ESTABLISHED, wire.Error) = {
    val errorExtOpt: Option[ErrorExt] = Some(errorCode).map(makeError).map(ErrorExt.generateFrom)
    Tuple2(data.copy(localError = errorExtOpt), errorExtOpt.get.error)
  }

  def ackAddFail(cmd: CMD_ADD_HTLC, cause: ChannelException, channelUpdate: wire.ChannelUpdate): HostedFsmState = {
    log.warning(s"PLGN PHC, ${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    Channel.replyToCommand(self, RES_ADD_FAILED(channelUpdate = Some(channelUpdate), t = cause, c = cmd), cmd)
    stay
  }

  def failTimedoutOutgoing(localAdds: Set[wire.UpdateAddHtlc], data: HC_DATA_ESTABLISHED): Unit =
    for {
      add <- localAdds
      originChan <- data.commitments.originChannels.get(add.id)
      reasonChain = HtlcResult OnChainFail HtlcsTimedoutDownstream(channelId, Set apply add)
      _ = log.info(s"PLGN PHC, failing timed out outgoing htlc, hash=${add.paymentHash} origin=$originChan")
    } kit.relayer ! RES_ADD_SETTLED(originChan, add, result = reasonChain)
}
