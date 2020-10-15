package fr.acinq.hc.app.dbo

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.ShortChannelId
import slick.jdbc.PostgresProfile
import fr.acinq.hc.app.Tools
import scodec.bits.BitVector
import slick.sql.SqlAction


case class CollectedHostedUpdates(announces: Map[ShortChannelId, ChannelAnnouncement], updates: Set[ChannelUpdate] = Set.empty) {
  def +(announce: ChannelAnnouncement): CollectedHostedUpdates = CollectedHostedUpdates(announces.updated(announce.shortChannelId, announce), updates)
  def +(update: ChannelUpdate): CollectedHostedUpdates = CollectedHostedUpdates(announces, updates + update)
}

case class HostedUpdates(shortChannelId: ShortChannelId, channelAnnounce: ChannelAnnouncement,
                         channelUpdate1: Option[ChannelUpdate], channelUpdate2: Option[ChannelUpdate],
                         localStamp: Long)

class HostedUpdatesDb(db: PostgresProfile.backend.Database) {
  def toAnnounce(raw: String): ChannelAnnouncement = channelAnnouncementCodec.decode(BitVector fromValidHex raw).require.value
  def toUpdate(raw: String): ChannelUpdate = channelUpdateCodec.decode(BitVector fromValidHex raw).require.value

  val removeThreshold: Long = 28.days.toSeconds
  val staleThreshold: Long = 14.days.toSeconds

  def getPHCMap(now: Long): Map[ShortChannelId, HostedUpdates] = {
    val rawUpdates = Blocking.txRead(Updates.findNotStaleCompiled(now - staleThreshold).result, db)

    val updates = for (Tuple8(_, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, _, _, localStamp) <- rawUpdates)
      yield HostedUpdates(ShortChannelId(shortChannelId), toAnnounce(channelAnnounce), channelUpdate1.map(toUpdate), channelUpdate2.map(toUpdate), localStamp)

    Tools.toMapBy[ShortChannelId, HostedUpdates](updates, _.shortChannelId)
  }

  def pruneOldAnnounces(now: Long): Int = Blocking.txWrite(Updates.findAnnounceOldUpdatableCompiled(now - removeThreshold).delete, db)

  def addAnnounce(announce: ChannelAnnouncement): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.insert(announce.shortChannelId.toLong, channelAnnouncementCodec.encode(announce).require.toHex)

  def addUpdate(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    if (Announcements isNode1 update.channelFlags) addUpdate1(update) else addUpdate2(update)

  private def addUpdate1(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.update1st(update.shortChannelId.toLong, channelUpdateCodec.encode(update).require.toHex, update.timestamp)

  private def addUpdate2(update: ChannelUpdate): SqlAction[Int, PostgresProfile.api.NoStream, Effect] =
    Updates.update2nd(update.shortChannelId.toLong, channelUpdateCodec.encode(update).require.toHex, update.timestamp)
}
