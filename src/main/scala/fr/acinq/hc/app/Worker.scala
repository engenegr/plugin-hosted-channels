package fr.acinq.hc.app

import fr.acinq.hc.app.HC._
import scala.concurrent.duration._
import fr.acinq.hc.app.network.{HostedSync, PHC}
import fr.acinq.hc.app.dbo.{HostedChannelsDb, HostedUpdatesDb}
import fr.acinq.hc.app.channel.{HC_DATA_ESTABLISHED, HostedChannel}
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, Terminated}
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected, UnknownMessageReceived}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.SupervisorStrategy.Resume
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair.Kit
import scodec.Attempt


object Worker {
  case object TickSendGossip { val label = "TickSendGossip" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object TickRemoveIdleChannels { val label = "TickRemoveIdleChannels" }
}

class Worker(kit: Kit, updatesDb: HostedUpdatesDb, channelsDb: HostedChannelsDb, vals: Vals) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(10.minutes, PHC.tickStaggeredBroadcastThreshold, self, Worker.TickSendGossip)
  context.system.scheduler.scheduleWithFixedDelay(60.minutes, 60.minutes, self, Worker.TickClearIpAntiSpam)
  context.system.scheduler.scheduleWithFixedDelay(2.days, 2.days, self, Worker.TickRemoveIdleChannels)

  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)

  val remoteNode2Connection = mutable.Map.empty[PublicKey, PeerConnectedWrap]

  val inMemoryHostedChannels: HashBiMap[PublicKey, ActorRef] = HashBiMap.create[PublicKey, ActorRef]

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  val hostedSync: ActorRef = context actorOf Props(classOf[HostedSync], kit, updatesDb, vals.phcConfig, self)

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
      ???

    case peerMessage: UnknownMessageReceived
      if hostedRoutingTags.contains(peerMessage.message.tag) =>
      remoteNode2Connection.get(peerMessage.nodeId).foreach { wrap =>
        fr.acinq.hc.app.wire.Codecs.decodeHostedMessage(peerMessage.message) match {
          case Attempt.Successful(_: QueryPublicHostedChannels) => hostedSync ! HostedSync.SendSyncTo(wrap)
          case Attempt.Successful(_: ReplyPublicHostedChannelsEnd) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
          case Attempt.Failure(error) => logger.info(s"PLGN PHC, decode fail, error=${error.message}")
          case _ => logger.info(s"PLGN PHC, decode mismatch, tag=${peerMessage.message.tag}")
        }
      }

    case peerMessage: UnknownMessageReceived
      if hostedMessageTags.contains(peerMessage.message.tag) =>
      ???

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)

    case Worker.TickRemoveIdleChannels =>
      logger.info(s"PLGN PHC, in-memory HC=${inMemoryHostedChannels.size}")
      inMemoryHostedChannels.values.forEach(_ ! Worker.TickRemoveIdleChannels)
  }

  override def supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(-1, 5.seconds) { case error: Throwable =>
      logger.info(s"PLGN PHC, error in child=${error.getMessage}")
      Resume
    }

  def spawnNewChannel(remoteNodeId: PublicKey): ActorRef = {
    val channel = context actorOf Props(classOf[HostedChannel], kit, vals)
    inMemoryHostedChannels.put(remoteNodeId, channel)
    context.watch(channel)
    channel
  }

  def restoreChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnNewChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restoreOrSpawnNew(remoteNodeId: PublicKey): ActorRef =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId).map(restoreChannel) match {
      case None => spawnNewChannel(remoteNodeId)
      case Some(channel) => channel
    }

  def restoreOrNotFound(remoteNodeId: PublicKey)(whenRestored: ActorRef => Unit): Unit =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId).map(restoreChannel) match {
      case None => sender ! s"HC not found, nodeId=${remoteNodeId.toString}"
      case Some(channel) => whenRestored(channel)
    }
}
