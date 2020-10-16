package fr.acinq.hc.app.dbo

import fr.acinq.hc.app.dbo.PHC._
import scala.concurrent.duration._
import fr.acinq.hc.app.dbo.PHCNetwork._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.ShortChannelId
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Crypto
import scodec.bits.BitVector
import fr.acinq.hc.app.Tools
import slick.sql.SqlAction


object PHC {
  val staleThreshold: Long = 14.days.toSeconds // Remove remote ChannelUpdate if it has not been refreshed for this much days
  val tickUpdateThreshold: Long = 5.days.toSeconds // Periodically refresh and resend ChannelUpdate gossip for local PHC with a given interval
  val tickRequestFullSyncThreshold: Long = 2.days.toSeconds // Periodically request full PHC gossip sync from one of supporting peers with a given interval
  val tickStaggeredBroadcastThreshold: Long = 30.minutes.toSeconds // Periodically send collected PHC gossip messages to supporting peers with a given interval
  val reAnnounceThreshold: Long = 10.days.toSeconds // Re-initiate full announce/update procedure for PHC if last ChannelUpdate has been sent this many days ago
}

case class PHC(shortChannelId: ShortChannelId, channelAnnounce: ChannelAnnouncement, channelUpdate1: Option[ChannelUpdate] = None, channelUpdate2: Option[ChannelUpdate] = None) {
  def nodeIdToShortId = List(channelAnnounce.nodeId1 -> channelAnnounce.shortChannelId, channelAnnounce.nodeId2 -> channelAnnounce.shortChannelId)
  def tuple: (ShortChannelId, PHC) = (shortChannelId, this)
}

object PHCNetwork {
  type ShortChannelIdSet = Set[ShortChannelId]
}

case class PHCNetwork(channels: Map[ShortChannelId, PHC], perNode: Map[Crypto.PublicKey, ShortChannelIdSet] = Map.empty) {

  def isNewAnnounceAcceptable(announce: ChannelAnnouncement): Boolean = {
    val notTooMuchNode1PHCs = perNode.getOrElse(announce.nodeId1, Set.empty).size < 2
    val notTooMuchNode2PHCs = perNode.getOrElse(announce.nodeId1, Set.empty).size < 2
    val computedShortId = Tools.hostedShortChanId(announce.nodeId1.value, announce.nodeId2.value)
    computedShortId == announce.shortChannelId && Tools.isPHC(announce) && notTooMuchNode1PHCs && notTooMuchNode2PHCs
  }

  // Add announce without updates
  def withNewAnnounce(announce: ChannelAnnouncement): PHCNetwork = {
    val nodeId1ToShortIds = perNode.getOrElse(announce.nodeId1, Set.empty) + announce.shortChannelId
    val nodeId2ToShortIds = perNode.getOrElse(announce.nodeId2, Set.empty) + announce.shortChannelId
    val perNode1 = perNode.updated(announce.nodeId1, nodeId1ToShortIds).updated(announce.nodeId2, nodeId2ToShortIds)
    copy(channels = channels + PHC(announce.shortChannelId, announce).tuple, perNode = perNode1)
  }

  // Update announce, but keep everything else
  def withUpdatedAnnounce(announce1: ChannelAnnouncement): PHCNetwork = channels.get(announce1.shortChannelId) match {
    case Some(gossip) => copy(channels = channels + gossip.copy(channelAnnounce = announce1).tuple)
    case None => this
  }


  def isUpdateAcceptable(update: ChannelUpdate): Boolean = channels.get(update.shortChannelId) match {
    case Some(gossip) if Announcements isNode1 update.channelFlags => gossip.channelUpdate1.forall(_.timestamp < update.timestamp)
    case Some(gossip) => gossip.channelUpdate2.forall(_.timestamp < update.timestamp)
    case None => false
  }

  // Refresh an update, but keep everything else
  def withUpdate(update: ChannelUpdate): PHCNetwork = {
    val update1Opt: Option[ChannelUpdate] = Some(update)

    channels.get(update.shortChannelId) match {
      case Some(gossip) if Announcements isNode1 update.channelFlags => copy(channels = channels + gossip.copy(channelUpdate1 = update1Opt).tuple)
      case Some(gossip) => copy(channels = channels + gossip.copy(channelUpdate2 = update1Opt).tuple)
      case None => this
    }
  }
}

class HostedUpdatesDb(db: PostgresProfile.backend.Database) {
  def toAnnounce(raw: String): ChannelAnnouncement = channelAnnouncementCodec.decode(BitVector fromValidHex raw).require.value
  def toUpdate(raw: String): ChannelUpdate = channelUpdateCodec.decode(BitVector fromValidHex raw).require.value

  def getState: PHCNetwork = {
    val updates: Seq[PHC] = for {
      Tuple7(_, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, _, _) <- Blocking.txRead(Updates.model.result, db)
    } yield PHC(ShortChannelId(shortChannelId), toAnnounce(channelAnnounce), channelUpdate1 map toUpdate, channelUpdate2 map toUpdate)

    val channelMap = Tools.toMapBy[ShortChannelId, PHC](updates, _.shortChannelId)
    val perNodeMap = updates.flatMap(_.nodeIdToShortId).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    PHCNetwork(channelMap, perNodeMap)
  }

  def pruneUpdateLessAnnounces: Int = Blocking.txWrite(Updates.findAnnounceDeletableCompiled.delete, db)

  def pruneOldUpdates1(now: Long): Int = Blocking.txWrite(Updates.findUpdate1stOldUpdatableCompiled(now - staleThreshold).update(None), db)

  def pruneOldUpdates2(now: Long): Int = Blocking.txWrite(Updates.findUpdate2ndOldUpdatableCompiled(now - staleThreshold).update(None), db)

  def addAnnounce(announce: ChannelAnnouncement): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.insert(announce.shortChannelId.toLong, channelAnnouncementCodec.encode(announce).require.toHex)

  def addUpdate(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    if (Announcements isNode1 update.channelFlags) addUpdate1(update)
    else addUpdate2(update)

  private def addUpdate1(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.update1st(update.shortChannelId.toLong, channelUpdateCodec.encode(update).require.toHex, update.timestamp)

  private def addUpdate2(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.update2nd(update.shortChannelId.toLong, channelUpdateCodec.encode(update).require.toHex, update.timestamp)
}
