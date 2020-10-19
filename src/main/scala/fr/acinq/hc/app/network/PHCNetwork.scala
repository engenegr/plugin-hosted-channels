package fr.acinq.hc.app.network

import fr.acinq.eclair.wire._
import fr.acinq.hc.app.Tools._
import scala.concurrent.duration._
import fr.acinq.hc.app.network.PHCNetwork.ShortChannelIdSet
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.ShortChannelId
import fr.acinq.hc.app.wire.Codecs
import fr.acinq.hc.app.PHCConfig
import fr.acinq.bitcoin.Crypto


object PHC {
  val staleThreshold: Long = 14.days.toSeconds // Remove ChannelUpdate if it has not been refreshed for this much days
  val tickUpdateThreshold: Long = 5.days.toSeconds // Periodically refresh and resend ChannelUpdate gossip for local PHC with a given interval
  val tickRequestFullSyncThreshold: FiniteDuration = 2.days // Periodically request full PHC gossip sync from one of supporting peers with a given interval
  val tickStaggeredBroadcastThreshold: Long = 10.minutes.toSeconds // Periodically send collected PHC gossip messages to supporting peers with a given interval
  val reAnnounceThreshold: Long = 10.days.toSeconds // Re-initiate full announce/update procedure for PHC if last ChannelUpdate has been sent this many days ago
}

case class PHC(shortChannelId: ShortChannelId, channelAnnounce: ChannelAnnouncement, channelUpdate1: Option[ChannelUpdate] = None, channelUpdate2: Option[ChannelUpdate] = None) {
  lazy val announcesList: List[UnknownMessage] = for (message <- channelAnnounce +: channelUpdate1 ++: channelUpdate2 ++: Nil) yield Codecs.toUnknownAnnounceMessage(message, isGossip = false)
  def nodeIdToShortId = List(channelAnnounce.nodeId1 -> channelAnnounce.shortChannelId, channelAnnounce.nodeId2 -> channelAnnounce.shortChannelId)
  def tuple: (ShortChannelId, PHC) = (shortChannelId, this)

  def verifySig(cu: ChannelUpdate): Boolean = isNode1(cu) match {
    case true => Announcements.checkSig(cu, channelAnnounce.nodeId1)
    case false => Announcements.checkSig(cu, channelAnnounce.nodeId2)
  }
}

object PHCNetwork {
  type ShortChannelIdSet = Set[ShortChannelId]
}

case class PHCNetwork(channels: Map[ShortChannelId, PHC],
                      perNode: Map[Crypto.PublicKey, ShortChannelIdSet],
                      messagesReceived: Set[AnnouncementMessage] = Set.empty) {

  def isAnnounceAcceptable(announce: ChannelAnnouncement): Boolean =
    hostedShortChanId(announce.nodeId1.value, announce.nodeId2.value) == announce.shortChannelId &&
      announce.bitcoinSignature1 == announce.nodeSignature1 && announce.bitcoinSignature2 == announce.nodeSignature2 &&
      announce.bitcoinKey1 == announce.nodeId1 && announce.bitcoinKey2 == announce.nodeId2

  def isNewAnnounceAcceptable(announce: ChannelAnnouncement, phcConfig: PHCConfig): Boolean = {
    val notTooMuchNode1PHCs = perNode.getOrElse(announce.nodeId1, Set.empty).size < phcConfig.maxPerNode
    val notTooMuchNode2PHCs = perNode.getOrElse(announce.nodeId1, Set.empty).size < phcConfig.maxPerNode
    isAnnounceAcceptable(announce) && notTooMuchNode1PHCs && notTooMuchNode2PHCs
  }

  // Add announce without updates
  def withNewAnnounce(announce: ChannelAnnouncement): PHCNetwork = {
    val nodeId1ToShortIds = perNode.getOrElse(announce.nodeId1, Set.empty) + announce.shortChannelId
    val nodeId2ToShortIds = perNode.getOrElse(announce.nodeId2, Set.empty) + announce.shortChannelId
    val perNode1 = perNode.updated(announce.nodeId1, nodeId1ToShortIds).updated(announce.nodeId2, nodeId2ToShortIds)
    copy(channels = channels + PHC(announce.shortChannelId, announce).tuple, perNode = perNode1, messagesReceived = messagesReceived + announce)
  }

  // Update announce, but keep everything else
  def withUpdatedAnnounce(announce1: ChannelAnnouncement): PHCNetwork = channels.get(announce1.shortChannelId) match {
    case Some(gossip) => copy(channels = channels + gossip.copy(channelAnnounce = announce1).tuple, messagesReceived = messagesReceived + announce1)
    case None => this
  }

  def isUpdateAcceptable(update: ChannelUpdate): Boolean = channels.get(update.shortChannelId) match {
    case Some(gossip) if isNode1(update) => gossip.channelUpdate1.forall(_.timestamp < update.timestamp)
    case Some(gossip) => gossip.channelUpdate2.forall(_.timestamp < update.timestamp)
    case None => false
  }

  // Refresh an update, but keep everything else
  def withUpdate(update: ChannelUpdate): PHCNetwork = {
    val newUpdateOpt: Option[ChannelUpdate] = Some(update)
    val phcNetwork1 = copy(messagesReceived = messagesReceived + update)

    channels.get(update.shortChannelId) match {
      case Some(gossip) if isNode1(update) => phcNetwork1.copy(channels = channels + gossip.copy(channelUpdate1 = newUpdateOpt).tuple)
      case Some(gossip) => phcNetwork1.copy(channels = channels + gossip.copy(channelUpdate2 = newUpdateOpt).tuple)
      case None => this
    }
  }
}
