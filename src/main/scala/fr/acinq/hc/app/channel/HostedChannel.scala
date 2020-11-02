package fr.acinq.hc.app.channel

import fr.acinq.hc.app.{InvokeHostedChannel, PeerConnectedWrap, Vals}
import fr.acinq.hc.app.dbo.HostedChannelsDb
import akka.actor.Actor
import fr.acinq.eclair


object HostedChannel {
  case class RemoteInvoke(remoteInvoke: InvokeHostedChannel, wrap: PeerConnectedWrap)
}

class HostedChannel(kit: eclair.Kit, channelsDb: HostedChannelsDb, vals: Vals) extends Actor {
  override def receive: Receive = ???
}