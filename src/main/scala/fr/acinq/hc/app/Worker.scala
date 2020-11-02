package fr.acinq.hc.app

import fr.acinq.hc.app.HC._
import fr.acinq.hc.app.channel._
import scala.concurrent.duration._
import fr.acinq.hc.app.network.{HostedSync, PHC}
import fr.acinq.hc.app.dbo.{HostedChannelsDb, HostedUpdatesDb}
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, Terminated}
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected, UnknownMessageReceived}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.SupervisorStrategy.Resume
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair
import scodec.Attempt


object Worker {
  case object TickSendGossip { val label = "TickSendGossip" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object TickRemoveIdleChannels { val label = "TickRemoveIdleChannels" }

  val hostedChanDenied: eclair.wire.Error = eclair.wire.Error(ByteVector32.Zeroes, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
}

class Worker(kit: eclair.Kit, updatesDb: HostedUpdatesDb, channelsDb: HostedChannelsDb, vals: Vals) extends Actor with Logging {
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
      channelRefOpt(systemMessage.nodeId).foreach(_ ! systemMessage)
      remoteNode2Connection.remove(systemMessage.nodeId)

    case systemMessage: PeerConnected if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(HCFeature.plugin) =>
      // Do not try to locate or create an HC just yet since peer may not be interested in initialization of HC
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

    case UnknownMessageReceived(_, nodeId, message, _) if chanIdMessageTags.contains(message.tag) =>
      // Messages with channel id presume a target HC exists, it must not be spawned if not actually found
      Tuple2(Codecs decodeHasChanIdMessage message, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _) => logger.debug(s"PLGN PHC, hasChanId decode fail, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(msg), null) => restoreAndTellOrElse(msg, nodeId)(_ => logger debug s"PLGN PHC, hasChanId no target, peer=${nodeId.toString}")
        case (Attempt.Successful(msg), channelRef) => channelRef ! msg
      }

    case UnknownMessageReceived(_, nodeId, message, _) if hostedMessageTags.contains(message.tag) =>
      // Order matters here: routing messages should be sent to sync actor, invoke should be checked for anti-spam
      Tuple3(Codecs decodeHostedMessage message, remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.debug(s"PLGN PHC, hosted decode fail, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(_: ReplyPublicHostedChannelsEnd), Some(peerWrap), _) => hostedSync ! HostedSync.GotAllSyncFrom(peerWrap)
        case (Attempt.Successful(_: QueryPublicHostedChannels), Some(peerWrap), _) => hostedSync ! HostedSync.SendSyncTo(peerWrap)

        case (Attempt.Successful(_: InvokeHostedChannel), Some(peerWrap), null) if ipAntiSpam(peerWrap.remoteIp) > vals.maxNewChansPerIpPerHour => peerWrap sendHasChannelIdMsg Worker.hostedChanDenied
        case (Attempt.Successful(msg: InvokeHostedChannel), Some(peerWrap), null) => restoreAndTellOrElse(HostedChannel.RemoteInvoke(msg, peerWrap), nodeId)(msg => spawnChannel(nodeId) ! msg)
        case (Attempt.Successful(msg: InvokeHostedChannel), Some(peerWrap), channelRef) => channelRef ! HostedChannel.RemoteInvoke(msg, peerWrap)
        case (Attempt.Successful(msg: HostedChannelMessage), _, null) => restoreAndTellOrElse(msg, nodeId)(Tools.none)
        case (Attempt.Successful(msg: HostedChannelMessage), _, channelRef) => channelRef ! msg
      }

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

  def channelRefOpt(nodeId: PublicKey): Option[ActorRef] =
    Option(inMemoryHostedChannels get nodeId)

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

  def restoreAndTellOrElse[T](message: T, nodeId: PublicKey)(orElse: T => Unit): Unit =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId = nodeId) match {
      case Some(data) => spawnPreparedChannel(data) ! message
      case None => orElse(message)
    }
}
