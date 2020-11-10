package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app.wire.Codecs.{LocalOrRemoteUpdateWithChannelId, UpdateWithChannelId}
import fr.acinq.eclair.channel.{CLOSED, CMD_SIGN, ChannelErrorOccurred, Helpers, HtlcResult, HtlcsTimedoutDownstream, InvalidChainHash, InvalidFinalScript, LocalError, NORMAL, OFFLINE, RES_ADD_SETTLED, SYNCING, State}
import fr.acinq.hc.app.{AnnouncementSignature, HCParams, HostedChannelMessage, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, PeerConnectedWrap, StateUpdate, Tools, Vals, Worker}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.hc.app.dbo.HostedChannelsDb
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.{AnnouncementMessage, AnnouncementSignatures, ChannelAnnouncement, ChannelUpdate, HasChannelId, UpdateAddHtlc}

import scala.collection.mutable
import akka.actor.{ActorRef, FSM}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.io.{PeerDisconnected, UnknownMessageReceived}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.hc.app.channel.HostedChannel.SendAnnouncements
import fr.acinq.hc.app.network.PHC
import fr.acinq.hc.app.wire.Codecs
import scodec.bits.ByteVector


object HostedChannel {
  case object SendAnnouncements { val label = "SendAnnouncements" }
}

class HostedChannel(kit: Kit, connections: mutable.Map[PublicKey, PeerConnectedWrap], remoteNodeId: PublicKey, channelsDb: HostedChannelsDb,
                    hostedSync: ActorRef, vals: Vals) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  lazy val channelId: ByteVector32 = Tools.hostedChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  lazy val shortChannelId: ShortChannelId = Tools.hostedShortChanId(kit.nodeParams.nodeId.value, remoteNodeId.value)

  lazy val initParams: HCParams = vals.hcOverrideMap.get(remoteNodeId).map(_.params).getOrElse(vals.hcDefaultParams)

  startTimerWithFixedDelay(SendAnnouncements.label, SendAnnouncements, PHC.tickAnnounceThreshold)

  context.system.eventStream.subscribe(channel = classOf[CurrentBlockCount], subscriber = self)

  startWith(OFFLINE, HC_NOTHING)

  when(OFFLINE) {
    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) =>
      if (data.errorExt.isDefined) {
        goto(CLOSED) using data
      } else {
        stay using data
      }

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) =>
      // We could have transitioned into OFFLINE from CLOSED
      if (data.errorExt.isDefined) {
        goto(CLOSED)
      } else if (data.commitments.isHost) {
        // Host awaits for remote invoke
        stay
      } else {
        // Client is the one who tries to invoke a channel on reconnect
        val refundKey = data.commitments.lastCrossSignedState.refundScriptPubKey
        goto(SYNCING) sendingHosted InvokeHostedChannel(kit.nodeParams.chainHash, refundKey)
      }

    case Event(CMD_HOSTED_LOCAL_INVOKE(_, scriptPubKey, secret), HC_NOTHING) =>
      val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, scriptPubKey, secret)
      goto(SYNCING) using HC_DATA_CLIENT_WAIT_HOST_INIT(scriptPubKey) sendingHosted invokeMsg

    case Event(remoteInvoke: InvokeHostedChannel, HC_NOTHING) =>
      if (kit.nodeParams.chainHash != remoteInvoke.chainHash) stay sendingHasChannelId makeError(InvalidChainHash(channelId, kit.nodeParams.chainHash, remoteInvoke.chainHash).getMessage)
      else if (Helpers.Closing isValidFinalScriptPubkey remoteInvoke.refundScriptPubKey) goto(SYNCING) using HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(remoteInvoke) sendingHosted initParams.initMsg
      else stay sendingHasChannelId makeError(InvalidFinalScript(channelId).getMessage)

    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) =>
      if (data.commitments.isHost) {
        // Invoke is always expected from client side, never from host
        goto(SYNCING) sendingHosted data.commitments.lastCrossSignedState
      } else {
        stay
      }

    case Event(Worker.TickRemoveIdleChannels, HC_NOTHING) => stop(FSM.Normal)

    case Event(Worker.TickRemoveIdleChannels, data: HC_DATA_ESTABLISHED) =>
      // Client channels are never idle, instead they always try to reconnect on becoming OFFLINE
      if (data.commitments.isHost && data.commitments.timedOutOutgoingHtlcs(Long.MaxValue).isEmpty) {
        stop(FSM.Normal)
      } else {
        stay
      }
  }

  when(SYNCING) {
    case Event(hostInit: InitHostedChannel, data: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      if (hostInit.liabilityDeadlineBlockdays < vals.hcDefaultParams.liabilityDeadlineBlockdays) stop(FSM.Normal) sendingHasChannelId makeError("Proposed liability deadline is too low")
      else if (hostInit.minimalOnchainRefundAmountSatoshis > vals.hcDefaultParams.initMsg.minimalOnchainRefundAmountSatoshis) stop(FSM.Normal) sendingHasChannelId makeError("Proposed minimal refund is too high")
      else if (hostInit.initialClientBalanceMsat > vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) sendingHasChannelId makeError("Proposed init balance for us is larger than capacity")
      else if (hostInit.channelCapacityMsat < vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) sendingHasChannelId makeError("Proposed channel capacity is too low")
      else {
        val localUnsignedLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = hostInit, blockDay = currentBlockDay,
          localBalanceMsat = hostInit.initialClientBalanceMsat, remoteBalanceMsat = hostInit.channelCapacityMsat - hostInit.initialClientBalanceMsat,
          localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
        val commitments = restoreEmptyCommitments(localUnsignedLCSS.withLocalSigOfRemote(kit.nodeParams.privateKey), isHost = false)
        val localSU: StateUpdate = commitments.lastCrossSignedState.stateUpdate(isTerminal = true)
        stay using HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commitments) sendingHosted localSU
      }

    case Event(clientSU: StateUpdate, data: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val fullySignedLCSS = LastCrossSignedState(data.invoke.refundScriptPubKey, initHostedChannel = initParams.initMsg, blockDay = clientSU.blockDay,
        localBalanceMsat = initParams.initMsg.channelCapacityMsat, remoteBalanceMsat = MilliSatoshi(0L), localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil,
        outgoingHtlcs = Nil, remoteSigOfLocal = clientSU.localSigOfRemoteLCSS, localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

      if (!fullySignedLCSS.verifyRemoteSig(remoteNodeId)) stop(FSM.Normal) sendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else if (math.abs(clientSU.blockDay - currentBlockDay) > 1) stop(FSM.Normal) sendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, clientSU.blockDay).getMessage)
      else goto(NORMAL) storingAndUsing restoreEmptyData(fullySignedLCSS, isHost = true) storingSecret data.invoke.finalSecret sendingHosted fullySignedLCSS.stateUpdate(isTerminal = true)

    case Event(hostSU: StateUpdate, data: HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commitments.lastCrossSignedState.copy(remoteSigOfLocal = hostSU.localSigOfRemoteLCSS)
      if (!fullySignedLCSS.verifyRemoteSig(remoteNodeId)) stop(FSM.Normal) sendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else if (math.abs(hostSU.blockDay - currentBlockDay) > 1) stop(FSM.Normal) sendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, hostSU.blockDay).getMessage)
      else if (data.commitments.lastCrossSignedState.remoteUpdates != hostSU.localUpdates) stop(FSM.Normal) sendingHasChannelId makeError("Proposed remote/local update number mismatch")
      else if (data.commitments.lastCrossSignedState.localUpdates != hostSU.remoteUpdates) stop(FSM.Normal) sendingHasChannelId makeError("Proposed local/remote update number mismatch")
      else goto(NORMAL) storingAndUsing restoreEmptyData(fullySignedLCSS, isHost = false)

    // LOCAL MISSES CHANNEL

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      // Client has sent InvokeHostedChannel and was expecting for InitHostedChannel from host
      restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost = false)

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      // Host sent InitHostedChannel and was expecting for StateUpdate from host
      restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost = true)

    // REMOTE HOST MISSES CHANNEL

    case Event(_: InitHostedChannel, data: HC_DATA_ESTABLISHED) if !data.commitments.isHost =>
      stay sendingHosted data.commitments.lastCrossSignedState

    // NORMAL PATHWAY

    case Event(remoteLCSS: LastCrossSignedState, data: HC_DATA_ESTABLISHED) =>
      val weAreAhead = data.commitments.lastCrossSignedState.isAhead(remoteLCSS)
      val weAreEven = data.commitments.lastCrossSignedState.isEven(remoteLCSS)
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
      val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)

      if (!isRemoteSigOk) localSuspend(data, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
      else if (!isLocalSigOk) localSuspend(data, ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG)
      else if (weAreAhead || weAreEven) {
        // Resend our local pending updates (adds, fails and fulfills) but retain our current cross-signed state since we are ahead or even
        val commits1 = syncAndResend(data.commitments, data.commitments.futureUpdates, data.commitments.lastCrossSignedState, data.commitments.localSpec)
        goto(NORMAL) using data.copy(commitments = commits1)
      } else {
        data.commitments.findState(remoteLCSS).headOption map { commits1 =>
          val leftOvers = data.commitments.futureUpdates.diff(commits1.futureUpdates)
          // They have one of our future states, we also may have local pending updates (adds, fails and fulfills)
          // this may happen if we send a message followed by StateUpdate, then disconnect (they have our future state)
          val commits2 = syncAndResend(commits1, leftOvers, remoteLCSS.reverse, commits1.nextLocalSpec)
          goto(NORMAL) storingAndUsing data.copy(commitments = commits2)
        } getOrElse {
          // The history is disconnected, looks like we have lost a channel state
          if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
            // It's safer to just suspend a channel in this case and check payments manually
            // because since we have lost a channel state we might also lose a payment database
            localSuspend(data, ErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC)
          } else {
            val data1 = restoreEmptyData(remoteLCSS.reverse, data.commitments.isHost)
            goto(NORMAL) storingAndUsing data1 sendingHosted data1.commitments.lastCrossSignedState
          }
        }
      }
  }

  when(NORMAL) {
    case Event(HostedChannel.SendAnnouncements, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      // Last update could have been sent way too long ago and other nodes might have removed PHC announcement by now
      val lastUpdateTooLongAgo = data.localChannelUpdate.timestamp < System.currentTimeMillis - PHC.reAnnounceThreshold
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1 = data.copy(localChannelUpdate = update1)

      data1.channelAnnouncement match {
        case None => stay sendingHosted Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = true)
        case Some(announce) if lastUpdateTooLongAgo => stay storingAndUsing data1 publishing announce publishing data1.localChannelUpdate
        case _ => stay storingAndUsing data1 publishing data1.localChannelUpdate
      }

    case Event(remoteSig: AnnouncementSignature, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val localSig = Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = false)
      val announce = Tools.makePHCAnnouncement(kit.nodeParams, localSig, remoteSig, shortChannelId, remoteNodeId)
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1 = data.copy(channelAnnouncement = Some(announce), localChannelUpdate = update1)
      val isSigOK = Announcements.checkSigs(announce)

      if (isSigOK && remoteSig.wantsReply) {
        log.info(s"PLGN PHC, announcing PHC and sending sig reply, peer=$remoteNodeId")
        stay storingAndUsing data1 sendingHosted localSig publishing announce publishing data1.localChannelUpdate
      } else if (isSigOK) {
        log.info(s"PLGN PHC, announcing PHC without sig reply, peer=$remoteNodeId")
        stay storingAndUsing data1 publishing announce publishing data1.localChannelUpdate
      } else {
        log.info(s"PLGN PHC, announce sig check failed, peer=$remoteNodeId")
        stay
      }

    case Event(cmd: CMD_TURN_PUBLIC, data: HC_DATA_ESTABLISHED) =>
      val commits1 = data.commitments.copy(announceChannel = true)
      val data1 = data.copy(commitments = commits1)
      self ! HostedChannel.SendAnnouncements
      sender ! CMDResponseSuccess(cmd)
      stay storingAndUsing data1

    case Event(cmd: CMD_TURN_PRIVATE, data: HC_DATA_ESTABLISHED) =>
      val commits1 = data.commitments.copy(announceChannel = false)
      val data1 = data.copy(commitments = commits1)
      sender ! CMDResponseSuccess(cmd)
      stay storingAndUsing data1
  }

  when(CLOSED) {
    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) =>
      if (data.errorExt.isDefined) {
        // Other side may have lost a state for a closed channel, in this case send LCSS, then an error
        stay sendingHosted data.commitments.lastCrossSignedState sendingHasChannelId data.errorExt.get.error
      } else {
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
        val errorExtOpt: Option[ErrorExt] = Some(remoteError).map(ErrorExt.generateFrom)
        goto(CLOSED) storingAndUsing data.copy(remoteError = errorExtOpt)
      } else {
        goto(CLOSED)
      }

    case Event(remoteError: wire.Error, _) =>
      // Channel is not persisted so it was in establishing state
      val description: String = ErrorExt extractDescription remoteError
      log.info(s"PLGN PHC, HC establish remote error, error=$description")
      stop(FSM.Normal)

    case Event(c: CurrentBlockCount, data: HC_DATA_ESTABLISHED) =>
      val failedIds = failTimedoutOutgoing(c.blockCount, data)
      if (failedIds.nonEmpty) {
        val commits1 = data.commitments.copy(timedOutToPeerHtlcLeftOverIds = data.commitments.timedOutToPeerHtlcLeftOverIds ++ failedIds)
        localSuspend(data.copy(commitments = commits1), ErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC)
      } else {
        stay
      }
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit class MyState(state: HostedFsmState) {
    def sendingHasChannelId(messages: HasChannelId *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(conn => messages foreach conn.sendHasChannelIdMsg)
      state
    }

    def sendingHosted(messages: HostedChannelMessage *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(conn => messages foreach conn.sendHostedChannelMsg)
      state
    }

    def publishing(message: AnnouncementMessage): HostedFsmState = {
      val unknownMessage = Codecs.toUnknownAnnounceMessage(message, isGossip = true)
      hostedSync ! UnknownMessageReceived(peer = null, kit.nodeParams.nodeId, unknownMessage, connectionInfo = null)
      state
    }

    def storingAndUsing(data: HC_DATA_ESTABLISHED): HostedFsmState = {
      channelsDb.updateOrAddNewChannel(data)
      state using data
    }

    def storingSecret(secret: ByteVector): HostedFsmState = {
      channelsDb.updateSecretById(remoteNodeId, secret)
      state
    }
  }

  initialize()

  def makeError(content: String): wire.Error = wire.Error(channelId, content)

  def currentBlockDay: Long = kit.nodeParams.currentBlockHeight / 144

  def makeStateUpdate(data: HC_DATA_ESTABLISHED): StateUpdate = {
    val localUnsignedLCSS1 = data.commitments.nextLocalUnsignedLCSS(currentBlockDay)
    localUnsignedLCSS1.withLocalSigOfRemote(kit.nodeParams.privateKey).stateUpdate(isTerminal = false)
  }

  def makeChannelUpdate(localLCSS: LastCrossSignedState, enable: Boolean): ChannelUpdate =
    Announcements.makeChannelUpdate(kit.nodeParams.chainHash, kit.nodeParams.privateKey, remoteNodeId, shortChannelId,
      initParams.cltvDelta, initParams.htlcMinimumMsat.msat, initParams.feeBase, initParams.feeProportionalMillionths,
      localLCSS.initHostedChannel.channelCapacityMsat, enable)

  def restoreEmptyCommitments(localLCSS: LastCrossSignedState, isHost: Boolean): HostedCommitments = {
    val localCommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat)
    HostedCommitments(isHost, kit.nodeParams.nodeId, remoteNodeId, channelId, localCommitmentSpec, originChannels = Map.empty, lastCrossSignedState = localLCSS,
      futureUpdates = Nil, timedOutToPeerHtlcLeftOverIds = Set.empty, fulfilledByPeerHtlcLeftOverIds = Set.empty, announceChannel = false)
  }

  def restoreEmptyData(localLCSS: LastCrossSignedState, isHost: Boolean): HC_DATA_ESTABLISHED = {
    val localChannelUpdate: ChannelUpdate = makeChannelUpdate(localLCSS, enable = true)
    val commitments: HostedCommitments = restoreEmptyCommitments(localLCSS, isHost)
    HC_DATA_ESTABLISHED(commitments, localChannelUpdate = localChannelUpdate)
  }

  def restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost: Boolean): HostedFsmState = {
    log.info(s"restoring missing hosted channel with peer=${remoteNodeId.toString}")
    val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
    val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
    val data1 = restoreEmptyData(remoteLCSS.reverse, isHost)

    if (!isRemoteSigOk) stop(FSM.Normal) sendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG) // Their proposed signature does not match, an obvious error
    else if (!isLocalSigOk) stop(FSM.Normal) sendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG) // Our own signature does not match, means we did not sign it earlier
    else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) stop(FSM.Normal) sendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE)
    else goto(NORMAL) storingAndUsing data1 sendingHosted data1.commitments.lastCrossSignedState
  }

  def syncAndResend(commitments: HostedCommitments, leftOvers: List[LocalOrRemoteUpdateWithChannelId], lcss: LastCrossSignedState, spec: CommitmentSpec): HostedCommitments = {
    val commits1 = commitments.copy(futureUpdates = leftOvers.filter(_.isLeft), lastCrossSignedState = lcss, localSpec = spec)
    stay.sendingHosted(commits1.lastCrossSignedState).sendingHasChannelId(commits1.nextLocalUpdates:_*)
    if (commits1.nextLocalUpdates.nonEmpty) self ! CMD_SIGN
    commits1
  }

  def localSuspend(data: HC_DATA_ESTABLISHED, errorCode: String): HostedFsmState = {
    val errorExtOpt: Option[ErrorExt] = Some(errorCode).map(makeError).map(ErrorExt.generateFrom)
    goto(CLOSED) storingAndUsing data.copy(localError = errorExtOpt) sendingHasChannelId errorExtOpt.get.error
  }

  def failTimedoutOutgoing(blockHeight: Long, data: HC_DATA_ESTABLISHED): Set[Long] =
    for {
      add <- data.commitments.timedOutOutgoingHtlcs(blockHeight)
      originChannel <- data.commitments.originChannels.get(add.id)
      reasonChain = HtlcResult OnChainFail HtlcsTimedoutDownstream(htlcs = Set(add), channelId = channelId)
      _ = log.info(s"PLGN PHC, failing htlc with hash=${add.paymentHash} origin=$originChannel: htlc timed out")
      _ = kit.relayer ! RES_ADD_SETTLED(originChannel, add, reasonChain)
    } yield add.id
}