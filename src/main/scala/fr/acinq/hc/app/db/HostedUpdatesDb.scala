package fr.acinq.hc.app.db

import slick.jdbc.PostgresProfile.api._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.hc.app.network.{PHC, PHCNetwork}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.ShortChannelId
import slick.jdbc.PostgresProfile
import fr.acinq.hc.app.Tools
import scodec.bits.BitVector
import slick.sql.SqlAction


class HostedUpdatesDb(val db: PostgresProfile.backend.Database) {
  def toAnnounce(raw: String): ChannelAnnouncement = channelAnnouncementCodec.decode(BitVector fromValidHex raw).require.value
  def toUpdate(raw: String): ChannelUpdate = channelUpdateCodec.decode(BitVector fromValidHex raw).require.value

  def getState: PHCNetwork = {
    val updates: Seq[PHC] = for {
      Tuple7(_, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, _, _) <- Blocking.txRead(Updates.model.result, db)
    } yield PHC(ShortChannelId(shortChannelId), toAnnounce(channelAnnounce), channelUpdate1 map toUpdate, channelUpdate2 map toUpdate)

    val channelMap: Map[ShortChannelId, PHC] = Tools.toMapBy[ShortChannelId, PHC](updates)(_.shortChannelId)
    val channelSetPerNodeMap = updates.flatMap(_.nodeIdToShortId).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    PHCNetwork(channelMap, channelSetPerNodeMap, PHCNetwork.emptyUnsaved)
  }

  def pruneUpdateLessAnnounces: Int = Blocking.txWrite(Updates.findAnnounceDeletableCompiled.delete, db)

  def pruneOldUpdates1(now: Long): Int = Blocking.txWrite(Updates.findUpdate1stOldUpdatableCompiled(now - PHC.staleThreshold).update(None), db)

  def pruneOldUpdates2(now: Long): Int = Blocking.txWrite(Updates.findUpdate2ndOldUpdatableCompiled(now - PHC.staleThreshold).update(None), db)

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
