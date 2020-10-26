package fr.acinq.hc.app

import fr.acinq.hc.app.HC._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}
import fr.acinq.hc.app.network.{HostedSync, PHC}
import fr.acinq.hc.app.dbo.{HostedChannelsDb, HostedUpdatesDb}
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected, UnknownMessageReceived}
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair.Kit
import scodec.Attempt


object Worker {
  case object TickSendGossip { val label = "TickSendGossip" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }
}

class Worker(kit: Kit, updatesDb: HostedUpdatesDb, channelsDb: HostedChannelsDb, phcConfig: PHCConfig) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(10.minutes, PHC.tickStaggeredBroadcastThreshold, self, Worker.TickSendGossip)
  context.system.scheduler.scheduleWithFixedDelay(60.minutes, 60.minutes, self, Worker.TickClearIpAntiSpam)

  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)

  val remoteNode2Connection = mutable.Map.empty[PublicKey, PeerConnectedWrap]

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  val hostedSync: ActorRef = context actorOf Props(classOf[HostedSync], kit, updatesDb, phcConfig)

  override def receive: Receive = {
    case systemMessage: PeerDisconnected =>
      remoteNode2Connection.remove(systemMessage.nodeId)

    case systemMessage: PeerConnected if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(HCFeature.plugin) =>
      remoteNode2Connection(systemMessage.nodeId) = PeerConnectedWrap(systemMessage)

    case Worker.TickClearIpAntiSpam =>
      ipAntiSpam.clear

    case Worker.TickSendGossip =>
      hostedSync ! HostedSync.TickSendGossip(remoteNode2Connection.values.toList)

    case HostedSync.GetPeersForSync =>
      hostedSync ! HostedSync.PeersToSyncFrom(remoteNode2Connection.values.toList)

    case peerMessage: UnknownMessageReceived
      if announceTags.contains(peerMessage.message.tag) =>
      hostedSync ! peerMessage

    case peerMessage: UnknownMessageReceived
      if chanIdMessageTags.contains(peerMessage.message.tag) =>
      remoteNode2Connection.get(peerMessage.nodeId).foreach { wrap =>
        fr.acinq.hc.app.wire.Codecs.decodeHasChanIdMessage(peerMessage.message) match {
          case _ => // Do nothing
        }
      }

    case peerMessage: UnknownMessageReceived
      if hostedMessageTags.contains(peerMessage.message.tag) =>
      remoteNode2Connection.get(peerMessage.nodeId).foreach { wrap =>
        fr.acinq.hc.app.wire.Codecs.decodeHostedMessage(peerMessage.message) match {
          case Attempt.Successful(_: QueryPublicHostedChannels) => hostedSync ! HostedSync.SendSyncTo(wrap)
          case Attempt.Successful(_: ReplyPublicHostedChannelsEnd) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
          case _ => // Do nothing
        }
      }
  }
}
