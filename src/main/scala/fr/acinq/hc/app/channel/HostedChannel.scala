package fr.acinq.hc.app.channel

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.hc.app.Vals
import fr.acinq.eclair.Kit
import akka.actor.Actor


class HostedChannel(kit: Kit, vals: Vals, remoteNodeId: PublicKey) extends Actor {
  override def receive: Receive = ???
}