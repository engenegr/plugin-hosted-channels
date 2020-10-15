package fr.acinq.hc.app.dbo

import scala.concurrent.duration._
import fr.acinq.hc.app.dbo.Blocking._
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Index, Tag}

import slick.jdbc.PostgresProfile.backend.Database
import System.currentTimeMillis
import scala.concurrent.Await
import slick.sql.SqlAction
import akka.util.Timeout
import slick.dbio.Effect


object Blocking {
  type ByteArray = Array[Byte]
  type PendingRefund = Option[Long]
  type CompleteRefund = Option[String]
  type OptionalUpdate = Option[String]

  type RepLong = Rep[Long]
  type RepByteArray = Rep[ByteArray]

  val span: FiniteDuration = 25.seconds
  implicit val askTimeout: Timeout = Timeout(30.seconds)
  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)

  def createTablesIfNotExist(db: Database): Unit = {
    val tables = Seq(Channels.model).map(_.schema.createIfNotExists)
    Await.result(db.run(DBIO.sequence(tables).transactionally), span)
  }
}


object Channels {
  final val tableName = "channels"
  val model = TableQuery[Channels]

  type DbType = (Long, ByteArray, Long, Int, Boolean, CompleteRefund, PendingRefund, Long, Long, ByteArray, ByteArray)

  val insertCompiled = Compiled {
    for (x <- model) yield (x.channelId, x.shortChannelId, x.inFlightHtlcs, x.announceChannel, x.completeRefund, x.pendingRefund, x.lastBlockDay, x.createdAt, x.data, x.secret)
  }

  val findByChannelIdUpdatableCompiled = Compiled {
    (channelId: RepByteArray) => for (x <- model if x.channelId === channelId) yield (x.inFlightHtlcs, x.announceChannel, x.completeRefund, x.pendingRefund, x.lastBlockDay, x.data)
  }

  val findSecretUpdatableByIdCompiled = Compiled {
    (channelId: RepByteArray) => for (x <- model if x.channelId === channelId) yield x.secret
  }

  val findBySecretCompiled = Compiled {
    (secret: RepByteArray) => for (x <- model if x.secret === secret) yield x.data
  }

  val listHotChannelsCompiled = Compiled {
    for (x <- model if x.inFlightHtlcs > 0) yield x.data
  }

  val listPublicChannelsCompiled = Compiled {
    for (x <- model if x.announceChannel) yield x.data
  }
}

class Channels(tag: Tag) extends Table[Channels.DbType](tag, Channels.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  // These are not updatable
  def channelId: Rep[ByteArray] = column[ByteArray]("channel_id", O.Unique)
  def shortChannelId: Rep[Long] = column[Long]("short_channel_id", O.Unique)
  def createdAt: Rep[Long] = column[Long]("created_at")
  // These get derived from data when updating
  def inFlightHtlcs: Rep[Int] = column[Int]("in_flight_htlcs")
  def announceChannel: Rep[Boolean] = column[Boolean]("announce_channel")
  def completeRefund: Rep[CompleteRefund] = column[CompleteRefund]("complete_refund")
  def pendingRefund: Rep[PendingRefund] = column[PendingRefund]("pending_refund")
  def lastBlockDay: Rep[Long] = column[Long]("last_block_day")
  // These have special update rules
  def data: Rep[ByteArray] = column[ByteArray]("data")
  def secret: Rep[ByteArray] = column[ByteArray]("secret")

  def idx1: Index = index("channels__announce_channel__idx", announceChannel, unique = false) // Select these on startup
  def idx2: Index = index("channels__in_flight_htlcs__idx", inFlightHtlcs, unique = false) // Select these on startup
  def idx3: Index = index("channels__secret__idx", secret, unique = false) // Find these on user request

  def * = (id, channelId, shortChannelId, inFlightHtlcs, announceChannel, completeRefund, pendingRefund, lastBlockDay, createdAt, data, secret)
}


object Updates {
  final val tableName = "updates"
  val model = TableQuery[Updates]

  type DbType = (Long, Long, String, OptionalUpdate, OptionalUpdate, Long, Long, Long)

  val findNotStaleCompiled = Compiled {
    (threshold: RepLong) => model.filter(_.localStamp > threshold)
  }

  val findAnnounceOldUpdatableCompiled = Compiled {
    (threshold: RepLong) => model.filter(_.localStamp < threshold)
  }

  val findUpdate1stOldUpdatableCompiled = Compiled {
    (threshold: RepLong) => for (x <- model if x.update1Stamp < threshold) yield x.channelUpdate1
  }

  val findUpdate2ndOldUpdatableCompiled = Compiled {
    (threshold: RepLong) => for (x <- model if x.update2Stamp < threshold) yield x.channelUpdate2
  }

  def update1st(shortChannelId: Long, update: String, updateStamp: Long): SqlAction[Int, NoStream, Effect] = sqlu"""
    UPDATE #${Updates.tableName} SET channel_update_1_opt = $update, update_1_stamp = $updateStamp, local_stamp = $currentTimeMillis
    WHERE short_channel_id = $shortChannelId
  """

  def update2nd(shortChannelId: Long, update: String, updateStamp: Long): SqlAction[Int, NoStream, Effect] = sqlu"""
    UPDATE #${Updates.tableName} SET channel_update_2_opt = $update, update_2_stamp = $updateStamp, local_stamp = $currentTimeMillis
    WHERE short_channel_id = $shortChannelId
  """

  def insert(shortChannelId: Long, announce: String): SqlAction[Int, NoStream, Effect] = sqlu"""
    INSERT INTO #${Updates.tableName}(short_channel_id, channel_announce, local_stamp)
    VALUES ($shortChannelId, $announce, $currentTimeMillis)
    ON CONFLICT (short_channel_id) DO UPDATE
    SET channel_announce = $announce
  """
}

class Updates(tag: Tag) extends Table[Updates.DbType](tag, Updates.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def shortChannelId: Rep[Long] = column[Long]("short_channel_id", O.Unique)
  def channelAnnounce: Rep[String] = column[String]("channel_announce")
  def channelUpdate1: Rep[OptionalUpdate] = column[OptionalUpdate]("channel_update_1_opt", O Default None)
  def channelUpdate2: Rep[OptionalUpdate] = column[OptionalUpdate]("channel_update_2_opt", O Default None)
  def update1Stamp: Rep[Long] = column[Long]("update_1_stamp", O Default 0L)
  def update2Stamp: Rep[Long] = column[Long]("update_2_stamp", O Default 0L)
  def localStamp: Rep[Long] = column[Long]("local_stamp")

  def idx1: Index = index("updates__local_stamp__idx", localStamp, unique = false)
  def idx2: Index = index("updates__update_1_stamp__idx", update1Stamp, unique = false)
  def idx3: Index = index("updates__update_2_stamp__idx", update2Stamp, unique = false)

  def * = (id, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, update1Stamp, update2Stamp, localStamp)
}
