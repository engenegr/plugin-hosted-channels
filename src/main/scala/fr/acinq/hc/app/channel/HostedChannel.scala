package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app.channel.HostedChannel._
import fr.acinq.eclair.channel.{CLOSED, NORMAL, OFFLINE, SYNCING, State}
import fr.acinq.hc.app.{HostedChannelMessage, InvokeHostedChannel, PeerConnectedWrap, Vals, Worker}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.hc.app.dbo.HostedChannelsDb
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.HasChannelId
import scodec.bits.ByteVector
import akka.actor.FSM


object HostedChannel {
  case class RemoteInvoke(remoteInvoke: InvokeHostedChannel, wrap: PeerConnectedWrap)

  case class LocalInvoke(cmd: CMD_HOSTED_LOCAL_INVOKE, wrap: PeerConnectedWrap)
}

class HostedChannel(kit: Kit, remoteNodeId: PublicKey, channelsDb: HostedChannelsDb, vals: Vals) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  context.system.eventStream.subscribe(channel = classOf[CurrentBlockCount], subscriber = self)

  startWith(OFFLINE, HC_NOTHING)

  var activeConnection: Option[PeerConnectedWrap] = None

  when(OFFLINE) {
    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) =>
      // This is received before anything else if channel exists
      stay using data

    case Event(Worker.TickRemoveIdleChannels, HC_NOTHING) => stop(FSM.Normal)

    case Event(Worker.TickRemoveIdleChannels, data: HC_DATA_ESTABLISHED) =>
      // Client channels are never idle, instead they always try to reconnect on becoming OFFLINE
      if (data.commitments.isHost && data.commitments.timedOutOutgoingHtlcs(Long.MaxValue).isEmpty) {
        stop(FSM.Normal)
      } else {
        stay
      }

    case Event(wrap: PeerConnectedWrap, data: HC_DATA_ESTABLISHED) =>
      if (data.getError.isDefined) {
        goto(CLOSED) updatingConnection wrap
      } else if (data.commitments.isHost) {
        stay
      } else {
        val refundData = data.commitments.lastCrossSignedState.refundScriptPubKey
        val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, refundData)
        goto(SYNCING) updatingConnection wrap sendingHosted invokeMsg
      }

    case Event(LocalInvoke(CMD_HOSTED_LOCAL_INVOKE(_, scriptPubKey, secret), wrap), HC_NOTHING) =>
      val invokeMsg = InvokeHostedChannel(kit.nodeParams.chainHash, refundScriptPubKey = scriptPubKey, secret)
      goto(SYNCING) using HC_DATA_CLIENT_WAIT_HOST_INIT(scriptPubKey) updatingConnection wrap sendingHosted invokeMsg

    case Event(RemoteInvoke(clientInvoke, wrap), HC_NOTHING) =>
//      val p: HCParams = vals.hcOverrideMap.get(remoteNodeId).map(_.params).getOrElse(vals.hcDefaultParams)
//      val init = InitHostedChannel(p.maxHtlcValueInFlight, p.htlcMinimum, p.maxAcceptedHtlcs, p.defaultCapacity, p.liabilityDeadlineBlockdays, p.onChainRefundThreshold)
//      goto(WAIT_FOR_INIT_INTERNAL) using HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init) updatingConnection wrap receiving clientInvoke
      stay

    case Event(RemoteInvoke(invoke, wrap), _: HC_DATA_ESTABLISHED) => goto(SYNCING) updatingConnection wrap receiving invoke
  }

  when(SYNCING) {
    case _ => stay
  }

  when(NORMAL) {
    case _ => stay
  }

  when(CLOSED) {
    case _ => stay
  }

  whenUnhandled {
    case Event(wrap: PeerConnectedWrap, _: HC_DATA_ESTABLISHED) =>
      // This may happen if we get new connection while being connected
      stay updatingConnection wrap
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit class MyState(state: HostedFsmState) {
    def receiving(message: Any): HostedFsmState = {
      self ! message
      state
    }

    def updatingConnection(wrap: PeerConnectedWrap): HostedFsmState = {
      activeConnection = Some(wrap)
      state
    }

    def sendingHasChannelId(msg: HasChannelId): HostedFsmState = {
      activeConnection.foreach(_ sendHasChannelIdMsg msg)
      state
    }

    def sendingHosted(msg: HostedChannelMessage): HostedFsmState = {
      activeConnection.foreach(_ sendHostedChannelMsg msg)
      state
    }
  }

  initialize()
}