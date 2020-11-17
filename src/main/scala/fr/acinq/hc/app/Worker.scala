package fr.acinq.hc.app

import fr.acinq.hc.app.HC._
import fr.acinq.eclair.io._
import fr.acinq.hc.app.channel._
import scala.concurrent.duration._
import fr.acinq.hc.app.network.{HostedSync, PHC}
import akka.actor.{Actor, ActorRef, FSM, Props, Terminated}
import fr.acinq.hc.app.dbo.{HostedChannelsDb, HostedUpdatesDb}
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair
import scodec.Attempt


object Worker {
  case object HCPeerConnected

  case object TickSendGossip { val label = "TickSendGossip" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object TickRemoveIdleChannels { val label = "TickRemoveIdleChannels" }

  val notFound: FSM.Failure = FSM.Failure("HC with remote node is not found")

  val chanDenied: eclair.wire.Error = eclair.wire.Error(ByteVector32.Zeroes, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
}

class Worker(kit: eclair.Kit, updatesDb: HostedUpdatesDb, channelsDb: HostedChannelsDb, vals: Vals) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(10.minutes, PHC.tickStaggeredBroadcastThreshold, self, Worker.TickSendGossip)
  context.system.scheduler.scheduleWithFixedDelay(60.minutes, 60.minutes, self, Worker.TickClearIpAntiSpam)
  context.system.scheduler.scheduleWithFixedDelay(2.days, 2.days, self, Worker.TickRemoveIdleChannels)

  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)

  val hostedSync: ActorRef = context actorOf Props(classOf[HostedSync], kit, updatesDb, vals.phcConfig, self)

  val remoteNode2Connection: mutable.Map[PublicKey, PeerConnectedWrap] = mutable.Map.empty[PublicKey, PeerConnectedWrap]

  val inMemoryHostedChannels: HashBiMap[PublicKey, ActorRef] = HashBiMap.create[PublicKey, ActorRef]

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  override def receive: Receive = {
    case systemMessage: PeerConnected if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(HCFeature.plugin) =>
      remoteNode2Connection(systemMessage.nodeId) = PeerConnectedWrap(systemMessage)
      val refOpt = Option(inMemoryHostedChannels get systemMessage.nodeId)
      refOpt.foreach(_ ! Worker.HCPeerConnected)

    case systemMessage: PeerDisconnected =>
      remoteNode2Connection.remove(systemMessage.nodeId)
      val refOpt = Option(inMemoryHostedChannels get systemMessage.nodeId)
      refOpt.foreach(_ ! systemMessage)

    case Worker.TickClearIpAntiSpam =>
      ipAntiSpam.clear

    case Worker.TickSendGossip =>
      hostedSync ! HostedSync.TickSendGossip(remoteNode2Connection.values.toList)

    case HostedSync.GetPeersForSync =>
      hostedSync ! HostedSync.PeersToSyncFrom(remoteNode2Connection.values.toList)

    case peerMessage: UnknownMessageReceived if announceTags.contains(peerMessage.message.tag) =>
      // Gossip and sync messages are handled by sync actor
      hostedSync ! peerMessage

    case UnknownMessageReceived(_, nodeId, message, _) if hostedMessageTags.contains(message.tag) =>
      Tuple3(Codecs decodeHostedMessage message, remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.info(s"PLGN PHC, Hosted message decoding fail, tag=${message.tag}, peer=$nodeId")
        case (_, None, _) => logger.info(s"PLGN PHC, no connection found for message=${message.getClass.getSimpleName}, peer=$nodeId")
        case (Attempt.Successful(_: ReplyPublicHostedChannelsEnd), Some(wrap), _) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
        case (Attempt.Successful(_: QueryPublicHostedChannels), Some(wrap), _) => hostedSync ! HostedSync.SendSyncTo(wrap)

        // Special anti-spam handling for InvokeHostedChannel
        case (Attempt.Successful(_: InvokeHostedChannel), Some(wrap), null) if ipAntiSpam(wrap.remoteIp) > vals.maxNewChansPerIpPerHour => wrap sendHasChannelIdMsg Worker.chanDenied
        case (Attempt.Successful(invoke: InvokeHostedChannel), _, null) => restore(nodeId)(_ !> Worker.HCPeerConnected !> invoke)(spawnChannel(nodeId) !> Worker.HCPeerConnected !> invoke)
        case (Attempt.Successful(_: HostedChannelMessage), _, null) => logger.info(s"PLGN PHC, no target for HostedMessage, tag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(hosted: HostedChannelMessage), _, channelRef) => channelRef ! hosted
      }

    case UnknownMessageReceived(_, nodeId, message, _) if chanIdMessageTags.contains(message.tag) =>
      Tuple3(Codecs decodeHasChanIdMessage message, remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.info(s"PLGN PHC, HasChannelId message decoding fail, tag=${message.tag}, peer=$nodeId")
        case (_, None, _) => logger.info(s"PLGN PHC, no connection found for message=${message.getClass.getSimpleName}, peer=$nodeId")
        case (_, _, null) => logger.info(s"PLGN PHC, no target for HasChannelIdMessage, tag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(msg), _, channelRef) => channelRef ! msg
      }

    case cmd: HC_CMD_LOCAL_INVOKE =>
      val isConnected = remoteNode2Connection.contains(cmd.remoteNodeId)
      val isInDb = channelsDb.getChannelByRemoteNodeId(cmd.remoteNodeId).nonEmpty
      val isInMemory = Option(inMemoryHostedChannels get cmd.remoteNodeId).nonEmpty
      if (isInMemory || isInDb) sender ! FSM.Failure("HC with remote node already exists")
      else if (!isConnected) sender ! FSM.Failure("Not yet connected to remote peer")
      else spawnChannel(cmd.remoteNodeId) !> Worker.HCPeerConnected !> cmd

    // Peer may be disconnected when commands are issued
    // Channel must be able to handle command in any state

    case cmd: HasRemoteNodeIdHostedCommand =>
      Option(inMemoryHostedChannels get cmd.remoteNodeId) match {
        case None => restore(cmd.remoteNodeId)(_ forward cmd)(sender ! Worker.notFound)
        case Some(channelRef) => channelRef forward cmd
      }

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)

    case Worker.TickRemoveIdleChannels =>
      logger.info(s"PLGN PHC, in-memory HCs=${inMemoryHostedChannels.size}")
      inMemoryHostedChannels.values.forEach(_ ! Worker.TickRemoveIdleChannels)
  }

  def spawnChannel(nodeId: PublicKey): ActorRef = {
    val props = Props(classOf[HostedChannel], kit, remoteNode2Connection, nodeId, channelsDb, hostedSync, vals)
    val channelRef = inMemoryHostedChannels.put(nodeId, context actorOf props)
    context watch channelRef
  }

  def spawnPreparedChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restore(nodeId: PublicKey)(onFound: ActorRef => Unit)(onNotFound: => Unit): Unit =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId = nodeId) match {
      case Some(data) => onFound(me spawnPreparedChannel data)
      case None => onNotFound
    }

  implicit class MultiSender(channel: ActorRef) {
    def !>(message: Any): MultiSender = {
      channel forward message
      this
    }
  }
}
