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
  val tickUpdateThreshold: FiniteDuration = 5.days // Periodically refresh and resend ChannelUpdate gossip for local PHC with a given interval
  val tickRequestFullSyncThreshold: FiniteDuration = 2.days // Periodically request full PHC gossip sync from one of supporting peers with a given interval
  val tickStaggeredBroadcastThreshold: FiniteDuration = 10.minutes // Periodically send collected PHC gossip messages to supporting peers with a given interval
  val reAnnounceThreshold: Long = 10.days.toSeconds // Re-initiate full announce/update procedure for PHC if last ChannelUpdate has been sent this many days ago
}

case class PHC(shortChannelId: ShortChannelId, channelAnnounce: ChannelAnnouncement, channelUpdate1: Option[ChannelUpdate] = None, channelUpdate2: Option[ChannelUpdate] = None) {
  lazy val orderedMessages: List[UnknownMessage] = for (message <- channelAnnounce +: channelUpdate1 ++: channelUpdate2 ++: Nil) yield Codecs.toUnknownAnnounceMessage(message, isGossip = false)
  def nodeIdToShortId = List(channelAnnounce.nodeId1 -> channelAnnounce.shortChannelId, channelAnnounce.nodeId2 -> channelAnnounce.shortChannelId)
  def tuple: (ShortChannelId, PHC) = (shortChannelId, this)

  def verifySig(update: ChannelUpdate): Boolean = {
    val isNode1 = Announcements.isNode1(update.channelFlags)
    if (isNode1) Announcements.checkSig(update, channelAnnounce.nodeId1)
    else Announcements.checkSig(update, channelAnnounce.nodeId2)
  }

  def isUpdateFresh(update: ChannelUpdate): Boolean = {
    val isNode1 = Announcements.isNode1(update.channelFlags)
    if (isNode1) channelUpdate1.forall(_.timestamp < update.timestamp)
    else channelUpdate2.forall(_.timestamp < update.timestamp)
  }

  def withUpdate(update: ChannelUpdate): PHC = {
    val newUpdateOpt: Option[ChannelUpdate] = Some(update)
    val isNode1 = Announcements.isNode1(update.channelFlags)
    if (isNode1) copy(channelUpdate1 = newUpdateOpt)
    else copy(channelUpdate2 = newUpdateOpt)
  }
}

object PHCNetwork {
  type ShortChannelIdSet = Set[ShortChannelId]
  val emptyUnsaved: MessagesReceived = MessagesReceived(Set.empty)
}

case class MessagesReceived(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate] = Set.empty) {
  def add(message: ChannelAnnouncement): MessagesReceived = copy(announces = announces + message)
  def add(message: ChannelUpdate): MessagesReceived = copy(updates = updates + message)
  def orderedMessages: Set[AnnouncementMessage] = announces ++ updates
}

case class PHCNetwork(channels: Map[ShortChannelId, PHC],
                      perNode: Map[Crypto.PublicKey, ShortChannelIdSet],
                      unsaved: MessagesReceived) {

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
  def addNewAnnounce(announce: ChannelAnnouncement): PHCNetwork = {
    val nodeId1ToShortIds = perNode.getOrElse(announce.nodeId1, Set.empty) + announce.shortChannelId
    val nodeId2ToShortIds = perNode.getOrElse(announce.nodeId2, Set.empty) + announce.shortChannelId
    val perNode1 = perNode.updated(announce.nodeId1, nodeId1ToShortIds).updated(announce.nodeId2, nodeId2ToShortIds)
    copy(channels = channels + PHC(announce.shortChannelId, announce).tuple, perNode = perNode1, unsaved = unsaved add announce)
  }

  // Update announce, but keep everything else
  def addUpdatedAnnounce(announce1: ChannelAnnouncement): PHCNetwork = channels.get(announce1.shortChannelId) match {
    case Some(phc) => copy(channels = channels + phc.copy(channelAnnounce = announce1).tuple, unsaved = unsaved add announce1)
    case None => this
  }

  def isUpdateAcceptable(update: ChannelUpdate): Boolean =
    channels.get(update.shortChannelId).exists(_ isUpdateFresh update)

  // Refresh an update, but keep everything else
  def addUpdate(update: ChannelUpdate): PHCNetwork = channels.get(update.shortChannelId) match {
    case Some(phc) => copy(channels = channels + phc.withUpdate(update).tuple, unsaved = unsaved add update)
    case None => this
  }
}
