package fr.acinq.hc.app.channel

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.hc.app.{InvokeHostedChannel, PeerConnectedWrap, Vals}
import fr.acinq.eclair.Kit
import akka.actor.Actor


object HostedChannel {
  case class RemoteInvoke(remoteInvoke: InvokeHostedChannel, wrap: PeerConnectedWrap)
}

class HostedChannel(kit: Kit, vals: Vals) extends Actor {
  override def receive: Receive = ???
}