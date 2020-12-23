package fr.acinq.hc.app

import fr.acinq.eclair.io._
import fr.acinq.hc.app.Worker._
import fr.acinq.hc.app.channel._
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props, Terminated}
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.hc.app.db.HostedChannelsDb
import com.google.common.collect.HashBiMap
import fr.acinq.hc.app.network.HostedSync
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.hc.app.wire.Codecs
import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair
import scodec.Attempt


object Worker {
  case object HCPeerConnected

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object TickRemoveIdleChannels { val label = "TickRemoveIdleChannels" }

  case class ClientChannels(channels: Seq[HC_DATA_ESTABLISHED] = Nil)

  val notFound: CMDResFailure = CMDResFailure("HC with remote node is not found")
}

class Worker(kit: eclair.Kit, hostedSync: ActorRef, channelsDb: HostedChannelsDb, vals: Vals) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(60.minutes, 60.minutes, self, TickClearIpAntiSpam)
  context.system.scheduler.scheduleWithFixedDelay(2.days, 2.days, self, TickRemoveIdleChannels)

  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)

  val inMemoryHostedChannels: HashBiMap[PublicKey, ActorRef] = HashBiMap.create[PublicKey, ActorRef]

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  override def receive: Receive = {
    case systemMessage: PeerConnected if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(HCFeature.plugin) =>
      HC.remoteNode2Connection addOne systemMessage.nodeId -> PeerConnectedWrapNormal(systemMessage)
      val refOpt = Option(inMemoryHostedChannels get systemMessage.nodeId)
      refOpt.foreach(_ ! HCPeerConnected)

    case systemMessage: PeerDisconnected =>
      HC.remoteNode2Connection subtractOne systemMessage.nodeId
      val refOpt = Option(inMemoryHostedChannels get systemMessage.nodeId)
      refOpt.foreach(_ ! systemMessage)

    case TickClearIpAntiSpam => ipAntiSpam.clear

    case peerMessage: UnknownMessageReceived if HC.announceTags.contains(peerMessage.message.tag) => hostedSync ! peerMessage

    case UnknownMessageReceived(_, nodeId, message, _) if HC.hostedMessageTags.contains(message.tag) =>
      Tuple3(Codecs decodeHostedMessage message, HC.remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.info(s"PLGN PHC, Hosted message decoding fail, messageTag=${message.tag}, peer=$nodeId")
        case (_, None, _) => logger.info(s"PLGN PHC, no connection found for message=${message.getClass.getSimpleName}, peer=$nodeId")
        case (Attempt.Successful(_: ReplyPublicHostedChannelsEnd), Some(wrap), _) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
        case (Attempt.Successful(_: QueryPublicHostedChannels), Some(wrap), _) => hostedSync ! HostedSync.SendSyncTo(wrap)

        // Special handling for InvokeHostedChannel: if chan exists neither in memory nor in db, then this is a new chan request and anti-spam rules apply
        case (Attempt.Successful(invoke: InvokeHostedChannel), Some(wrap), null) => restore(onNotFound = guardSpawn(nodeId, wrap, invoke), onFound = _ !> HCPeerConnected !> invoke)(nodeId)
        case (Attempt.Successful(_: HostedChannelMessage), _, null) => logger.info(s"PLGN PHC, no target for HostedMessage, messageTag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(hosted: HostedChannelMessage), _, channelRef) => channelRef ! hosted
      }

    case UnknownMessageReceived(_, nodeId, message, _) if HC.chanIdMessageTags.contains(message.tag) =>
      Tuple3(Codecs decodeHasChanIdMessage message, HC.remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.info(s"PLGN PHC, HasChannelId message decoding fail, tag=${message.tag}, peer=$nodeId")
        case (_, None, _) => logger.info(s"PLGN PHC, no connection found for message=${message.getClass.getSimpleName}, peer=$nodeId")
        case (_, _, null) => logger.info(s"PLGN PHC, no target for HasChannelIdMessage, tag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(msg), _, channelRef) => channelRef ! msg
      }

    case cmd: HC_CMD_LOCAL_INVOKE =>
      val isConnected = HC.remoteNode2Connection.contains(cmd.remoteNodeId)
      val isInDb = channelsDb.getChannelByRemoteNodeId(cmd.remoteNodeId).nonEmpty
      val isInMemory = Option(inMemoryHostedChannels get cmd.remoteNodeId).nonEmpty
      if (kit.nodeParams.nodeId == cmd.remoteNodeId) sender ! CMDResFailure("HC with itself is prohibited")
      else if (isInMemory || isInDb) sender ! CMDResFailure("HC with remote node already exists")
      else if (!isConnected) sender ! CMDResFailure("Not yet connected to remote peer")
      else spawnChannel(cmd.remoteNodeId) !> HCPeerConnected !> cmd

    // Peer may be disconnected when commands are issued
    // Channel must be able to handle command in any state

    case cmd: HasRemoteNodeIdHostedCommand =>
      Option(inMemoryHostedChannels get cmd.remoteNodeId) match {
        case None => restore(sender ! notFound, _ forward cmd)(cmd.remoteNodeId)
        case Some(channelRef) => channelRef forward cmd
      }

    case Terminated(channelRef) => inMemoryHostedChannels.inverse.remove(channelRef)

    case TickRemoveIdleChannels =>
      logger.info(s"PLGN PHC, in-memory=${inMemoryHostedChannels.size}")
      inMemoryHostedChannels.values.forEach(_ ! TickRemoveIdleChannels)

    case ClientChannels(channels) =>
      for (channelData <- channels) {
        val nodeId = channelData.commitments.remoteNodeId
        kit.switchboard ! Peer.Connect(nodeId, None)
        spawnPreparedChannel(channelData)
      }

    case PeerConnection.ConnectionResult.NoAddressFound(nodeId) =>
      // We have requested a connection for client HC, but no address was found
      logger.info(s"PLGN PHC, no address for client HC, peer=$nodeId")
  }

  def spawnChannel(nodeId: PublicKey): ActorRef = {
    val props = Props(classOf[HostedChannel], kit, nodeId, channelsDb, hostedSync, vals)
    val channelRef = inMemoryHostedChannels.put(nodeId, context actorOf props)
    context watch channelRef
  }

  def guardSpawn(nodeId: PublicKey, wrap: PeerConnectedWrap, invoke: InvokeHostedChannel): Unit = {
    // Spawn new HC requested by remote peer if that peer is in our override map (special handling) or if there are not too many such requests from remote IP
    if (vals.hcOverrideMap.contains(nodeId) || ipAntiSpam(wrap.remoteIp) < vals.maxNewChansPerIpPerHour) spawnChannel(nodeId) !> HCPeerConnected !> invoke
    else wrap sendHasChannelIdMsg eclair.wire.Error(ByteVector32.Zeroes, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
    // Record this request for anti-spam
    ipAntiSpam(wrap.remoteIp) += 1
  }

  def spawnPreparedChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restore(onNotFound: => Unit, onFound: ActorRef => Unit)(nodeId: PublicKey): Unit =
    channelsDb.getChannelByRemoteNodeId(nodeId) match {
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
