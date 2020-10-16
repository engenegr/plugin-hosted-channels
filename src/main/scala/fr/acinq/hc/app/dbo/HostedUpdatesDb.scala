package fr.acinq.hc.app.dbo

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.hc.app.dbo.HostedUpdates._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import slick.jdbc.PostgresProfile
import fr.acinq.hc.app.Tools
import scodec.bits.BitVector
import slick.sql.SqlAction


case class AnnouncementSeenFrom(seenFrom: Set[PublicKey], announcement: ChannelAnnouncement) {
  def tuple: (ShortChannelId, AnnouncementSeenFrom) = (announcement.shortChannelId, this)
}

case class UpdateSeenFrom(seenFrom: Set[PublicKey], update: ChannelUpdate) {
  def tuple: (ShortChannelId, UpdateSeenFrom) = (update.shortChannelId, this)
}

case class CollectedGossip(announces: Map[ShortChannelId, AnnouncementSeenFrom],
                           updates1: Map[ShortChannelId, UpdateSeenFrom] = Map.empty,
                           updates2: Map[ShortChannelId, UpdateSeenFrom] = Map.empty) {

  def add(announce: ChannelAnnouncement, from: PublicKey): CollectedGossip = announces.get(announce.shortChannelId) match {
    case Some(announceSeenFrom) => copy(announces = announces + AnnouncementSeenFrom(announceSeenFrom.seenFrom + from, announce).tuple)
    case None => copy(announces = announces + AnnouncementSeenFrom(seenFrom = Set(from), announce).tuple)
  }

  def add(update: ChannelUpdate, from: PublicKey): CollectedGossip =
    if (Announcements isNode1 update.channelFlags) addUpdate1(update, from)
    else addUpdate2(update, from)

  private def addUpdate1(update: ChannelUpdate, from: PublicKey): CollectedGossip = updates1.get(update.shortChannelId) match {
    case Some(updateSeenFrom) => copy(updates1 = updates1 + UpdateSeenFrom(updateSeenFrom.seenFrom + from, update).tuple)
    case None => copy(updates1 = updates1 + UpdateSeenFrom(seenFrom = Set(from), update).tuple)
  }

  private def addUpdate2(update: ChannelUpdate, from: PublicKey): CollectedGossip = updates2.get(update.shortChannelId) match {
    case Some(updateSeenFrom) => copy(updates2 = updates2 + UpdateSeenFrom(updateSeenFrom.seenFrom + from, update).tuple)
    case None => copy(updates2 = updates2 + UpdateSeenFrom(seenFrom = Set(from), update).tuple)
  }
}

object HostedUpdates {
  val staleThreshold: Long = 14.days.toSeconds // Remove remote ChannelUpdate if it has not been refreshed for this much days
  val tickUpdateThreshold: Long = 5.days.toSeconds // Periodically refresh and resend ChannelUpdate gossip for local PHC with a given interval
  val tickRequestFullSyncThreshold: Long = 2.days.toSeconds // Periodically request full PHC gossip sync from one of supporting peers with a given interval
  val tickStaggeredBroadcastThreshold: Long = 30.minutes.toSeconds // Periodically send collected PHC gossip messages to supporting peers with a given interval
  val reAnnounceThreshold: Long = 10.days.toSeconds // Re-initiate full announce/update procedure for PHC if last ChannelUpdate has been sent this many days ago
}

case class HostedUpdates(shortChannelId: ShortChannelId, channelAnnounce: ChannelAnnouncement,
                         channelUpdate1: Option[ChannelUpdate] = None, channelUpdate2: Option[ChannelUpdate] = None)

class HostedUpdatesDb(db: PostgresProfile.backend.Database) {
  def toAnnounce(raw: String): ChannelAnnouncement = channelAnnouncementCodec.decode(BitVector fromValidHex raw).require.value
  def toUpdate(raw: String): ChannelUpdate = channelUpdateCodec.decode(BitVector fromValidHex raw).require.value

  def getMap: Map[ShortChannelId, HostedUpdates] = {
    // Select all records which has not been deleted yet

    val updates: Seq[HostedUpdates] = for {
      Tuple7(_, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, _, _) <- Blocking.txRead(Updates.model.result, db)
    } yield HostedUpdates(ShortChannelId(shortChannelId), toAnnounce(channelAnnounce), channelUpdate1 map toUpdate, channelUpdate2 map toUpdate)
    Tools.toMapBy[ShortChannelId, HostedUpdates](updates, _.shortChannelId)
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
