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
import fr.acinq.hc.app.wire.Codecs
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

    case peerMessage: UnknownMessageReceived if announceTags.contains(peerMessage.message.tag) =>
      // Gossip and sync PHC announcement messages are fully handled by sync actor for better performance
      hostedSync ! peerMessage

    case peerMessage: UnknownMessageReceived if chanIdMessageTags.contains(peerMessage.message.tag) =>
      // Messages with channel id presume a target HC exists, it must not be spawned if not actually found
      Tuple2(Codecs decodeHasChanIdMessage peerMessage.message, inMemoryHostedChannels get peerMessage.nodeId) match {
        case (_: Attempt.Failure, _) => logger.info(s"PLGN PHC, hasChanId decode fail, tag=${peerMessage.message.tag}, peer=${peerMessage.nodeId.toString}")
        case (Attempt.Successful(msg), null) => restoreAndTellOrElse(peerMessage.nodeId, msg)(logger debug s"PLGN PHC, no target chan, peer=${peerMessage.nodeId.toString}")
        case (Attempt.Successful(msg), channelRef) => channelRef ! msg
      }

    case peerMessage: UnknownMessageReceived if hostedMessageTags.contains(peerMessage.message.tag) =>
//      remoteNode2Connection.get(peerMessage.nodeId).foreach { wrap =>
//        if (HC_INVOKE_HOSTED_CHANNEL_TAG == peerMessage.message.tag && ipAntiSpam(wrap.remoteIp) > vals.maxNewChansPerIpPerHour) {
//          wrap sendHasChannelIdMsg fr.acinq.eclair.wire.Error(ByteVector32.Zeroes, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
//          logger.info(s"PLGN PHC, new channel abuse, peer=${peerMessage.nodeId.toString}")
//        } else {
//          fr.acinq.hc.app.wire.Codecs.decodeHostedMessage(peerMessage.message) match {
//            case Attempt.Successful(_: QueryPublicHostedChannels) => hostedSync ! HostedSync.SendSyncTo(wrap)
//            case Attempt.Successful(_: ReplyPublicHostedChannelsEnd) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
//
//            case Attempt.Failure(error) => logger.info(s"PLGN PHC, decode fail, tag=${peerMessage.message.tag}, error=${error.message}")
//          }
//        }
//      }
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

  def spawnChannel(nodeId: PublicKey): ActorRef = {
    val channel = context actorOf Props(classOf[HostedChannel], kit, vals)
    inMemoryHostedChannels.put(nodeId, channel)
    // To receive Terminated once it stops
    context.watch(channel)
    channel
  }

  def spawnPreparedChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restoreAndTellOrElse(nodeId: PublicKey, message: Any)(orElse: => Unit): Unit =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId = nodeId) match {
      case Some(data) => spawnPreparedChannel(data) ! message
      case None => orElse
    }
}
