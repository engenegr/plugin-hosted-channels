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

  case class HotChannels(channels: Seq[HC_DATA_ESTABLISHED] = Nil)

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
    case systemMessage: PeerDisconnected =>
      remoteNode2Connection.remove(systemMessage.nodeId)
      channelRefOpt(systemMessage.nodeId).foreach(_ ! systemMessage)

    case systemMessage: PeerConnected if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(HCFeature.plugin) =>
      remoteNode2Connection(systemMessage.nodeId) = PeerConnectedWrap(systemMessage)
      channelRefOpt(systemMessage.nodeId).foreach(_ ! Worker.HCPeerConnected)

    case Worker.TickClearIpAntiSpam =>
      ipAntiSpam.clear

    case Worker.TickSendGossip =>
      hostedSync ! HostedSync.TickSendGossip(remoteNode2Connection.values.toList)

    case HostedSync.GetPeersForSync =>
      hostedSync ! HostedSync.PeersToSyncFrom(remoteNode2Connection.values.toList)

    case peerMessage: UnknownMessageReceived if announceTags.contains(peerMessage.message.tag) =>
      // Gossip and sync messages are handled by sync actor
      hostedSync ! peerMessage

    case UnknownMessageReceived(_, nodeId, message, _) if chanIdMessageTags.contains(message.tag) =>
      // Messages with channel id presume a target channel exists, do not spawn a new one if not actually found
      Tuple3(Codecs decodeHasChanIdMessage message, remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.debug(s"PLGN PHC, decoding fail, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(_), None, _) => logger.debug(s"PLGN PHC, peerless message, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(msg), _, null) => restoreOrElse(msg, nodeId)(me logNoTarget nodeId.toString)
        case (Attempt.Successful(msg), _, channelRef) => channelRef ! msg
      }

    case UnknownMessageReceived(_, nodeId, message, _) if hostedMessageTags.contains(message.tag) =>
      // Order matters here: routing messages should be sent to sync actor, invoke should be checked for anti-spam
      Tuple3(Codecs decodeHostedMessage message, remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.debug(s"PLGN PHC, decoding fail, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(_), None, _) => logger.debug(s"PLGN PHC, peerless message, tag=${message.tag}, peer=${nodeId.toString}")
        case (Attempt.Successful(_: ReplyPublicHostedChannelsEnd), Some(wrap), _) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
        case (Attempt.Successful(_: QueryPublicHostedChannels), Some(wrap), _) => hostedSync ! HostedSync.SendSyncTo(wrap)

        // Special anti-spam handling for InvokeHostedChannel
        case (Attempt.Successful(_: InvokeHostedChannel), Some(wrap), null) if ipAntiSpam(wrap.remoteIp) > vals.maxNewChansPerIpPerHour => wrap sendHasChannelIdMsg Worker.chanDenied
        case (Attempt.Successful(msg: InvokeHostedChannel), _, null) => restoreOrElse(msg, nodeId)(invoke => spawnChannel(nodeId) ! invoke)
        case (Attempt.Successful(msg: InvokeHostedChannel), _, channelRef) => channelRef ! msg

        case (Attempt.Successful(msg: HostedChannelMessage), _, null) => restoreOrElse(msg, nodeId)(me logNoTarget nodeId.toString)
        case (Attempt.Successful(msg: HostedChannelMessage), _, channelRef) => channelRef ! msg
      }

    case cmd: CMD_HOSTED_LOCAL_INVOKE =>
      val isInMemory = channelRefOpt(cmd.remoteNodeId).isEmpty
      val isInDb = channelsDb.getChannelByRemoteNodeId(cmd.remoteNodeId).isEmpty
      val hasConnection: Boolean = remoteNode2Connection.contains(cmd.remoteNodeId)
      if (isInMemory || isInDb) sender ! FSM.Failure("HC with remote node already exists")
      else if (!hasConnection) sender ! FSM.Failure("Not connected to remote node")
      else spawnChannel(cmd.remoteNodeId) forward cmd

    case cmd: HasRemoteNodeIdHostedCommand =>
      channelRefOpt(cmd.remoteNodeId).map(_ forward cmd) getOrElse {
        val notFoundFailure = FSM.Failure("HC with remote node is not found")
        restoreOrElse(cmd, cmd.remoteNodeId)(_ => sender ! notFoundFailure)
      }

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)

    case Worker.TickRemoveIdleChannels =>
      logger.info(s"PLGN PHC, in-memory HC=${inMemoryHostedChannels.size}")
      inMemoryHostedChannels.values.forEach(_ ! Worker.TickRemoveIdleChannels)

    case Worker.HotChannels(channels) =>
      // Client channels and those with HTLCs

      for (channelData <- channels) {
        spawnPreparedChannel(channelData)
        if (!channelData.commitments.isHost) {
          // Make sure core retries these in case of disconnect
          val nodeId = channelData.commitments.remoteNodeId
          kit.switchboard ! Peer.Connect(nodeId, None)
        }
      }

    case PeerConnection.ConnectionResult.NoAddressFound(nodeId) =>
      // We have requested a connection for HC where we are client, but no address was found
      logger.info(s"PLGN PHC, no address for client HC, peer=${nodeId.toString}")
  }

  def logNoTarget[T](nodeId: String)(msg: T): Unit = logger.debug(s"PLGN PHC, no target chan for msg=${msg.getClass.getName}, peer=$nodeId")

  def channelRefOpt(nodeId: PublicKey): Option[ActorRef] = Option(inMemoryHostedChannels get nodeId)

  def spawnChannel(nodeId: PublicKey): ActorRef = {
    val props = Props(classOf[HostedChannel], kit, remoteNode2Connection, nodeId, channelsDb, vals)
    context watch inMemoryHostedChannels.put(nodeId, context actorOf props)
  }

  def spawnPreparedChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restoreOrElse[T](message: T, nodeId: PublicKey)(orElse: T => Unit): Unit =
    channelsDb.getChannelByRemoteNodeId(remoteNodeId = nodeId) match {
      case Some(data) => spawnPreparedChannel(data) forward message
      case None => orElse(message)
    }
}
