package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app._
import fr.acinq.eclair.channel._
import scala.concurrent.duration._

import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import fr.acinq.hc.app.Tools.{DuplicateHandler, DuplicateShortId}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.io.{Peer, PeerDisconnected}
import fr.acinq.eclair.{channel, wire}
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, FSM}

import fr.acinq.hc.app.wire.Codecs.LocalOrRemoteUpdateWithChannelId
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.hc.app.dbo.HostedChannelsDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.hc.app.network.PHC
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import scodec.bits.ByteVector


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
    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) =>
      if (data.errorExt.isDefined || data.refundCompleteInfo.isDefined) {
        goto(CLOSED) using data
      } else {
        stay using data
      }

    case Event(Worker.HCPeerConnected, data: HC_DATA_ESTABLISHED) =>
      // We could have transitioned into OFFLINE from CLOSED, hence check again
      if (data.errorExt.isDefined || data.refundCompleteInfo.isDefined) {
        goto(CLOSED)
      } else if (data.commitments.isHost) {
        // Host awaits for remote invoke
        stay
      } else {
        // Client is the one who tries to invoke a channel on reconnect
        val refundKey = data.commitments.lastCrossSignedState.refundScriptPubKey
        goto(SYNCING) SendingHosted InvokeHostedChannel(kit.nodeParams.chainHash, refundKey)
      }

    case Event(CMD_HOSTED_LOCAL_INVOKE(_, scriptPubKey, secret), HC_NOTHING) =>
      val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, scriptPubKey, secret)
      goto(SYNCING) using HC_DATA_CLIENT_WAIT_HOST_INIT(scriptPubKey) SendingHosted invokeMsg

    case Event(remoteInvoke: InvokeHostedChannel, HC_NOTHING) =>
      if (kit.nodeParams.chainHash != remoteInvoke.chainHash) stay SendingHasChannelId makeError(InvalidChainHash(channelId, kit.nodeParams.chainHash, remoteInvoke.chainHash).getMessage)
      else if (Helpers.Closing isValidFinalScriptPubkey remoteInvoke.refundScriptPubKey) goto(SYNCING) using HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(remoteInvoke) SendingHosted initParams.initMsg
      else stay SendingHasChannelId makeError(InvalidFinalScript(channelId).getMessage)

    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) =>
      if (data.commitments.isHost && data.refundPendingInfo.isDefined) {
        // In case when refund has been requested we inform user immediately after connection is established
        // the reason is this should not be happening if user really has lost their access to this hosted channel
        goto(SYNCING) SendingHosted data.commitments.lastCrossSignedState SendingHosted data.refundPendingInfo.get
      } else if (data.commitments.isHost) {
        // Invoke is always expected from client side, never from host
        goto(SYNCING) SendingHosted data.commitments.lastCrossSignedState
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

  when(SYNCING, stateTimeout = 5.minutes) {
    case Event(StateTimeout, _: HC_DATA_ESTABLISHED) =>
      log.info(s"PLGN PHC, sync timeout for existing chan, peer=$remoteNodeId")
      stay.Disconnecting

    case Event(StateTimeout, _) =>
      log.info(s"PLGN PHC, sync timeout for new chan, peer=$remoteNodeId")
      stop(FSM.Normal)

    case Event(hostInit: InitHostedChannel, data: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      if (hostInit.liabilityDeadlineBlockdays < vals.hcDefaultParams.liabilityDeadlineBlockdays) stop(FSM.Normal) SendingHasChannelId makeError("Proposed liability deadline is too low")
      else if (hostInit.minimalOnchainRefundAmountSatoshis > vals.hcDefaultParams.initMsg.minimalOnchainRefundAmountSatoshis) stop(FSM.Normal) SendingHasChannelId makeError("Proposed minimal refund is too high")
      else if (hostInit.initialClientBalanceMsat > vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) SendingHasChannelId makeError("Proposed init balance for us is larger than capacity")
      else if (hostInit.channelCapacityMsat < vals.hcDefaultParams.initMsg.channelCapacityMsat) stop(FSM.Normal) SendingHasChannelId makeError("Proposed channel capacity is too low")
      else {
        val localUnsignedLCSS = LastCrossSignedState(data.refundScriptPubKey, initHostedChannel = hostInit, blockDay = currentBlockDay,
          localBalanceMsat = hostInit.initialClientBalanceMsat, remoteBalanceMsat = hostInit.channelCapacityMsat - hostInit.initialClientBalanceMsat,
          localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil, localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
        val commitments = restoreEmptyCommitments(localUnsignedLCSS.withLocalSigOfRemote(kit.nodeParams.privateKey), isHost = false)
        val localSU: StateUpdate = commitments.lastCrossSignedState.stateUpdate(isTerminal = true)
        stay using HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commitments) SendingHosted localSU
      }

    case Event(clientSU: StateUpdate, wait: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      val handler = new DuplicateHandler[HC_DATA_ESTABLISHED] { def insert(data: HC_DATA_ESTABLISHED): Boolean = channelsDb addNewChannel data }
      val fullySignedLCSS = LastCrossSignedState(wait.invoke.refundScriptPubKey, initHostedChannel = initParams.initMsg, blockDay = clientSU.blockDay,
        localBalanceMsat = initParams.initMsg.channelCapacityMsat, remoteBalanceMsat = MilliSatoshi(0L), localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil,
        outgoingHtlcs = Nil, remoteSigOfLocal = clientSU.localSigOfRemoteLCSS, localSigOfRemote = ByteVector64.Zeroes).withLocalSigOfRemote(kit.nodeParams.privateKey)

      val data = restoreEmptyData(fullySignedLCSS, isHost = true)
      val isLocalSigOk = !fullySignedLCSS.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(clientSU)

      if (isBlockDayWrong) stop(FSM.Normal) SendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, clientSU.blockDay).getMessage)
      else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else {
        handler.execute(data) match {
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
      }

    case Event(hostSU: StateUpdate, data: HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE) =>
      val fullySignedLCSS = data.commitments.lastCrossSignedState.copy(remoteSigOfLocal = hostSU.localSigOfRemoteLCSS)
      if (!fullySignedLCSS.verifyRemoteSig(remoteNodeId)) stop(FSM.Normal) SendingHasChannelId makeError(InvalidRemoteStateSignature(channelId).getMessage)
      else if (isBlockDayOutOfSync(hostSU)) stop(FSM.Normal) SendingHasChannelId makeError(InvalidBlockDay(channelId, currentBlockDay, hostSU.blockDay).getMessage)
      else if (data.commitments.lastCrossSignedState.remoteUpdates != hostSU.localUpdates) stop(FSM.Normal) SendingHasChannelId makeError("Proposed remote/local update number mismatch")
      else if (data.commitments.lastCrossSignedState.localUpdates != hostSU.remoteUpdates) stop(FSM.Normal) SendingHasChannelId makeError("Proposed local/remote update number mismatch")
      else goto(NORMAL) StoringAndUsing restoreEmptyData(fullySignedLCSS, isHost = false)

    // LOCAL MISSES CHANNEL

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_CLIENT_WAIT_HOST_INIT) =>
      // Client has sent InvokeHostedChannel and was expecting for InitHostedChannel from host
      restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost = false)

    case Event(remoteLCSS: LastCrossSignedState, _: HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE) =>
      // Host sent InitHostedChannel and was expecting for StateUpdate from host
      restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost = true)

    // REMOTE HOST MISSES CHANNEL

    case Event(_: InitHostedChannel, data: HC_DATA_ESTABLISHED) if !data.commitments.isHost =>
      stay SendingHosted data.commitments.lastCrossSignedState

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
          goto(NORMAL) StoringAndUsing data.copy(commitments = commits2)
        } getOrElse {
          // The history is disconnected, looks like we have lost a channel state
          if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) {
            // It's safer to just suspend a channel in this case and check payments manually
            // because since we have lost a channel state we might also lose a payment database
            localSuspend(data, ErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_IN_SYNC)
          } else {
            val data1 = restoreEmptyData(remoteLCSS.reverse, data.commitments.isHost)
            goto(NORMAL) StoringAndUsing data1 SendingHosted data1.commitments.lastCrossSignedState
          }
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
      val data1 = data.copy(localChannelUpdate = update1)

      data1.channelAnnouncement match {
        case None => stay SendingHosted Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = true)
        case Some(announce) if lastUpdateTooLongAgo => stay StoringAndUsing data1 Publishing announce Publishing data1.localChannelUpdate
        case _ => stay StoringAndUsing data1 Publishing data1.localChannelUpdate
      }

    case Event(remoteSig: AnnouncementSignature, data: HC_DATA_ESTABLISHED) if data.commitments.announceChannel =>
      val localSig = Tools.makePHCAnnouncementSignature(kit.nodeParams, data.commitments, shortChannelId, wantsReply = false)
      val announce = Tools.makePHCAnnouncement(kit.nodeParams, localSig, remoteSig, shortChannelId, remoteNodeId)
      val update1 = makeChannelUpdate(localLCSS = data.commitments.lastCrossSignedState, enable = true)
      val data1 = data.copy(channelAnnouncement = Some(announce), localChannelUpdate = update1)
      val isSigOK = Announcements.checkSigs(announce)

      if (isSigOK && remoteSig.wantsReply) {
        log.info(s"PLGN PHC, announcing PHC and sending sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 SendingHosted localSig Publishing announce Publishing data1.localChannelUpdate
      } else if (isSigOK) {
        log.info(s"PLGN PHC, announcing PHC without sig reply, peer=$remoteNodeId")
        stay StoringAndUsing data1 Publishing announce Publishing data1.localChannelUpdate
      } else {
        log.info(s"PLGN PHC, announce sig check failed, peer=$remoteNodeId")
        stay
      }

    case Event(cmd: CMD_TURN_PUBLIC, data: HC_DATA_ESTABLISHED) =>
      val commitments1 = data.commitments.copy(announceChannel = true)
      val data1: HC_DATA_ESTABLISHED = data.copy(commitments = commitments1)
      stay StoringAndUsing data1 AckingHosted cmd Receiving HostedChannel.SendAnnouncements

    case Event(cmd: CMD_TURN_PRIVATE, data: HC_DATA_ESTABLISHED) =>
      val commitments1 = data.commitments.copy(announceChannel = false)
      val data1 = data.copy(channelAnnouncement = None, commitments = commitments1)
      stay StoringAndUsing data1 AckingHosted cmd

    // Payments

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      data.commitments.sendAdd(cmd, kit.nodeParams.currentBlockHeight) match {
        case Right((commits1, add)) if cmd.commit => stay StoringAndUsing data.copy(commitments = commits1) Acking cmd SendingHasChannelId add Receiving CMD_SIGN
        case Right((commits1, add)) => stay StoringAndUsing data.copy(commitments = commits1) Acking cmd SendingHasChannelId add
        case Left(cause) => replyAddFailed(cmd, cause, data.localChannelUpdate)
      }

    // Peer adding and failing HTLCs is only accepted in NORMAL state

    case Event(add: wire.UpdateAddHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveAdd(add) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) => localSuspend(data, cause.getMessage)
      }

    case Event(fail: wire.UpdateFailHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveFail(fail) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) => localSuspend(data, cause.getMessage)
      }

    case Event(fail: wire.UpdateFailMalformedHtlc, data: HC_DATA_ESTABLISHED) =>
      data.commitments.receiveFailMalformed(fail) match {
        case Success(commits1) => stay using data.copy(commitments = commits1)
        case Failure(cause) => localSuspend(data, cause.getMessage)
      }

    case Event(CMD_SIGN, data: HC_DATA_ESTABLISHED) if data.commitments.futureUpdates.nonEmpty =>
      // Peer may stay online and send other messages without sending of remote StateUpdate due to a bug, make sure we disconnect then
      startSingleTimer(HostedChannel.RemoteUpdateTimeout.label, HostedChannel.RemoteUpdateTimeout, kit.nodeParams.revocationTimeout)
      stay SendingHosted makeStateUpdate(data.commitments)

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) if remoteSU.localSigOfRemoteLCSS == data.commitments.lastCrossSignedState.remoteSigOfLocal =>
      // Do nothing if we get a duplicate for new cross-signed state, this can often happen normally
      cancelTimer(HostedChannel.RemoteUpdateTimeout.label)
      stay

    case Event(remoteSU: StateUpdate, data: HC_DATA_ESTABLISHED) =>
      cancelTimer(HostedChannel.RemoteUpdateTimeout.label)

      val lcss1 = data.commitments.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(kit.nodeParams.privateKey)
      val commits1 = data.commitments.copy(lastCrossSignedState = lcss1, localSpec = data.commitments.nextLocalSpec, futureUpdates = Nil)
      val isRemoteSigOk = lcss1.verifyRemoteSig(remoteNodeId)
      val isBlockDayWrong = isBlockDayOutOfSync(remoteSU)

      if (isBlockDayWrong) {
        // Irregardless of what they send a blockday must be correct
        localSuspend(data, ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY)
      } else if (remoteSU.remoteUpdates < lcss1.localUpdates) {
        // Checking before sig because it may not match with many updates in-flight
        stay SendingHosted commits1.lastCrossSignedState.stateUpdate(isTerminal = false)
      } else if (!isRemoteSigOk) {
        localSuspend(data, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
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

        val lastStateOutgoingHtlcIds = data.commitments.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id)
        val stillOutgoingHtlcIds = commits1.localSpec.htlcs.collect(DirectedHtlc.outgoing).map(_.id)
        val completedOutgoingHtlcs = lastStateOutgoingHtlcIds -- stillOutgoingHtlcIds

        val commits2 = commits1.copy(originChannels = commits1.originChannels -- completedOutgoingHtlcs)
        val refundingResetData = data.copy(commitments = commits2, refundPendingInfo = None)
        val localSU = commits1.lastCrossSignedState.stateUpdate(isTerminal = true)
        stay StoringAndUsing refundingResetData SendingHosted localSU
      }
  }

  when(CLOSED) {
    case Event(_: InvokeHostedChannel, data: HC_DATA_ESTABLISHED) =>
      if (data.refundCompleteInfo.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId makeError(data.refundCompleteInfo.get)
      else if (data.refundPendingInfo.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHosted data.refundPendingInfo.get
      else if (data.errorExt.isDefined) stay SendingHosted data.commitments.lastCrossSignedState SendingHasChannelId data.errorExt.get.error
      else stay
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
        goto(CLOSED) StoringAndUsing data.copy(remoteError = errorExtOpt)
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

    case Event(cmd: CMD_ADD_HTLC, data: HC_DATA_ESTABLISHED) =>
      replyAddFailed(cmd, ChannelUnavailable(channelId), data.localChannelUpdate)
      val isUpdateEnabled = Announcements.isEnabled(data.localChannelUpdate.channelFlags)
      log.info(s"PLGN PHC, rejecting htlc request in state=$stateName, peer=$remoteNodeId")

      if (data.commitments.announceChannel && isUpdateEnabled) {
        // In order to reduce gossip spam, we don't disable the channel right away when disconnected
        // we will only emit a new ChannelUpdate with the disable flag set if someone tries to use it
        val disabledUpdate = makeChannelUpdate(data.commitments.lastCrossSignedState, enable = false)
        stay StoringAndUsing data.copy(localChannelUpdate = disabledUpdate) Publishing disabledUpdate
      } else {
        stay
      }

    // Refunds

    case Event(cmd: CMD_INIT_PENDING_REFUND, data: HC_DATA_ESTABLISHED) =>
      val refundPendingOpt = Some(System.currentTimeMillis).map(RefundPending)
      val data1 = data.copy(refundPendingInfo = refundPendingOpt)
      stay StoringAndUsing data1 AckingHosted cmd

    case Event(cmd: CMD_FINALIZE_REFUND, data: HC_DATA_ESTABLISHED) =>
      val enoughBlocksPassed = canFinalizeRefund(data.commitments.lastCrossSignedState)

      if (enoughBlocksPassed) {
        val refundCompleteInfoOpt = Some(cmd.info)
        log.info(s"PLGN PHC, finalized refund for HC with peer=$remoteNodeId")
        goto(CLOSED) StoringAndUsing data.copy(refundCompleteInfo = refundCompleteInfoOpt) AckingHosted cmd
      } else {
        val lastSignedDay = data.commitments.lastCrossSignedState.blockDay
        sender ! FSM.Failure(s"Not enough time have passed since blockDay=$lastSignedDay")
        stay
      }

    case Event(CMD_GET_HC_INFO, data: HC_DATA_ESTABLISHED) =>
      sender ! CMDResponseInfo(channelId, shortChannelId, stateName, data, data.commitments.nextLocalSpec)
      stay

    case Event(any, _) =>
      log.debug(s"PLGN PHC, failed to handle ${any.getClass.getSimpleName} in state=$stateName, data=${stateData.getClass.getSimpleName}, remoteNodeId=$remoteNodeId")
      stay
  }

  onTransition {
    case (SYNCING | CLOSED) -> NORMAL =>
      Tuple2(stateData, nextStateData) match {
        case (_, d1: HC_DATA_ESTABLISHED) if d1.commitments.announceChannel && !Announcements.isEnabled(d1.localChannelUpdate.channelFlags) => self ! HostedChannel.SendAnnouncements
        case (d0: HC_DATA_ESTABLISHED, d1: HC_DATA_ESTABLISHED) if !d1.commitments.announceChannel && d0.localChannelUpdate != d1.localChannelUpdate => sendUpdateToPeer(d1.localChannelUpdate)
        case (_, d1: HC_DATA_ESTABLISHED) if !d1.commitments.announceChannel => sendUpdateToPeer(d1.localChannelUpdate)
        case _ => log.debug(s"PLGN PHC, entered NORMAL state with wrong Data, peer=$remoteNodeId")
      }
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit class FsmStateExt(state: HostedFsmState) {
    def SendingHasChannelId(messages: wire.HasChannelId *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(conn => messages foreach conn.sendHasChannelIdMsg)
      state
    }

    def SendingHosted(messages: HostedChannelMessage *): HostedFsmState = {
      connections.get(remoteNodeId).foreach(conn => messages foreach conn.sendHostedChannelMsg)
      state
    }

    def Disconnecting: HostedFsmState = {
      val message = Peer.Disconnect(remoteNodeId)
      connections.get(remoteNodeId).foreach(_.info.peer ! message)
      state
    }

    def AckingHosted(cmd: HasRemoteNodeIdHostedCommand): HostedFsmState = {
      // Make sure hosted commands are forwarded, not told to this actor
      sender ! CMDResponseSuccess(cmd)
      state
    }

    def Acking(cmd: channel.Command): HostedFsmState = {
      val reply = RES_SUCCESS(cmd, channelId)

      cmd match {
        case cmd1: HasOptionalReplyToCommand => cmd1.replyTo_opt.foreach(_ ! reply)
        case cmd1: HasReplyToCommand if cmd1.replyTo == ActorRef.noSender => sender ! reply
        case cmd1: HasReplyToCommand => cmd1.replyTo ! reply
      }

      state
    }

    def Publishing(message: wire.AnnouncementMessage): HostedFsmState = {
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

  def sendUpdateToPeer(update: ChannelUpdate): Unit = connections.get(remoteNodeId).foreach(_ sendRoutingMsg update)

  def canFinalizeRefund(localLCSS: LastCrossSignedState): Boolean = localLCSS.blockDay + localLCSS.initHostedChannel.liabilityDeadlineBlockdays < currentBlockDay

  def makeStateUpdate(commits: HostedCommitments): StateUpdate = commits.nextLocalUnsignedLCSS(currentBlockDay).withLocalSigOfRemote(kit.nodeParams.privateKey).stateUpdate(isTerminal = false)

  def makeChannelUpdate(localLCSS: LastCrossSignedState, enable: Boolean): wire.ChannelUpdate =
    Announcements.makeChannelUpdate(kit.nodeParams.chainHash, kit.nodeParams.privateKey, remoteNodeId, shortChannelId,
      initParams.cltvDelta, initParams.htlcMinimumMsat.msat, initParams.feeBase, initParams.feeProportionalMillionths,
      localLCSS.initHostedChannel.channelCapacityMsat, enable)

  def restoreEmptyCommitments(localLCSS: LastCrossSignedState, isHost: Boolean): HostedCommitments = {
    val localCommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(0L.sat), localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat)
    HostedCommitments(isHost, kit.nodeParams.nodeId, remoteNodeId, channelId, localCommitmentSpec, originChannels = Map.empty, lastCrossSignedState = localLCSS,
      futureUpdates = Nil, timedOutToPeerHtlcLeftOverIds = Set.empty, fulfilledByPeerHtlcLeftOverIds = Set.empty, announceChannel = false)
  }

  def restoreEmptyData(localLCSS: LastCrossSignedState, isHost: Boolean): HC_DATA_ESTABLISHED = {
    val localChannelUpdate: wire.ChannelUpdate = makeChannelUpdate(localLCSS, enable = true)
    val commitments: HostedCommitments = restoreEmptyCommitments(localLCSS, isHost)
    HC_DATA_ESTABLISHED(commitments, localChannelUpdate = localChannelUpdate)
  }

  def restoreMissingChannel(remoteLCSS: LastCrossSignedState, isHost: Boolean): HostedFsmState = {
    log.info(s"restoring missing hosted channel with peer=${remoteNodeId.toString}")
    val isLocalSigOk = remoteLCSS.verifyRemoteSig(kit.nodeParams.nodeId)
    val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(remoteNodeId)
    val data1 = restoreEmptyData(remoteLCSS.reverse, isHost)

    if (!isRemoteSigOk) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG) // Their proposed signature does not match, an obvious error
    else if (!isLocalSigOk) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG) // Our own signature does not match, means we did not sign it earlier
    else if (remoteLCSS.incomingHtlcs.nonEmpty || remoteLCSS.outgoingHtlcs.nonEmpty) stop(FSM.Normal) SendingHasChannelId makeError(ErrorCodes.ERR_HOSTED_IN_FLIGHT_HTLC_IN_RESTORE)
    else goto(NORMAL) StoringAndUsing data1 SendingHosted data1.commitments.lastCrossSignedState
  }

  def syncAndResend(commitments: HostedCommitments, leftOvers: List[LocalOrRemoteUpdateWithChannelId], lcss: LastCrossSignedState, spec: CommitmentSpec): HostedCommitments = {
    val commits1 = commitments.copy(futureUpdates = leftOvers.filter(_.isLeft), lastCrossSignedState = lcss, localSpec = spec)
    stay.SendingHosted(commits1.lastCrossSignedState).SendingHasChannelId(commits1.nextLocalUpdates:_*)
    if (commits1.nextLocalUpdates.nonEmpty) self ! CMD_SIGN
    commits1
  }

  def localSuspend(data: HC_DATA_ESTABLISHED, errorCode: String): HostedFsmState = {
    val errorExtOpt: Option[ErrorExt] = Some(errorCode).map(makeError).map(ErrorExt.generateFrom)
    goto(CLOSED) StoringAndUsing data.copy(localError = errorExtOpt) SendingHasChannelId errorExtOpt.get.error
  }

  def replyAddFailed(cmd: CMD_ADD_HTLC, cause: ChannelException, channelUpdate: wire.ChannelUpdate): HostedFsmState = {
    log.warning(s"PLGN PHC, ${cause.getMessage} while processing cmd=${cmd.getClass.getSimpleName} in state=$stateName")
    val reply = RES_ADD_FAILED(channelUpdate = Some(channelUpdate), t = cause, c = cmd)
    val replyTo = if (cmd.replyTo == ActorRef.noSender) sender else cmd.replyTo
    replyTo ! reply
    stay
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