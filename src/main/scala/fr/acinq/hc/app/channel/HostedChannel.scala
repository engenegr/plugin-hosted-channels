package fr.acinq.hc.app.channel

import fr.acinq.hc.app.channel.HostedChannel._
import fr.acinq.hc.app.{InvokeHostedChannel, PeerConnectedWrap, Vals, Worker}
import fr.acinq.hc.app.dbo.HostedChannelsDb
import akka.actor.{Actor, FSM}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair
import fr.acinq.eclair.FSMDiagnosticActorLogging
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.channel.{OFFLINE, SYNCING, State, WAIT_FOR_INIT_INTERNAL}


object HostedChannel {
  case class RemoteInvoke(remoteInvoke: InvokeHostedChannel, wrap: PeerConnectedWrap)
}

class HostedChannel(kit: eclair.Kit, remoteNodeId: PublicKey, channelsDb: HostedChannelsDb, vals: Vals) extends FSM[State, HostedData] with FSMDiagnosticActorLogging[State, HostedData] {

  context.system.eventStream.subscribe(channel = classOf[CurrentBlockCount], subscriber = self)

  startWith(OFFLINE, HC_NOTHING)

  var activeConnection: Option[PeerConnectedWrap] = None

  when(OFFLINE) {
    case Event(data: HC_DATA_ESTABLISHED, HC_NOTHING) => stay using data

    case Event(Worker.TickRemoveIdleChannels, HC_NOTHING) => stop(FSM.Normal)

    case Event(Worker.TickRemoveIdleChannels, data: HC_DATA_ESTABLISHED) =>
      if (data.commitments.timedOutOutgoingHtlcs(Long.MaxValue).isEmpty) {
        stop(FSM.Normal)
      } else {
        stay
      }

    case Event(msg: RemoteInvoke, HC_NOTHING) => goto(WAIT_FOR_INIT_INTERNAL) andSetUp msg

    case Event(msg: RemoteInvoke, _: HC_DATA_ESTABLISHED) => goto(SYNCING) andSetUp msg
  }

  type HostedFsmState = FSM.State[fr.acinq.eclair.channel.State, HostedData]

  implicit class MyState(state: HostedFsmState) {
    def andSetUp(rInv: RemoteInvoke): HostedFsmState = {
      activeConnection = Some(rInv.wrap)
      self ! rInv.remoteInvoke
      state
    }
  }

  initialize()
}