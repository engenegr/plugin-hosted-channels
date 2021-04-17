package fr.acinq.hc.app.db

import scala.concurrent.duration._
import fr.acinq.hc.app.db.Blocking._
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Index, Tag}

import slick.jdbc.PostgresProfile.backend.Database
import scala.concurrent.Await
import slick.sql.SqlAction
import slick.dbio.Effect
import akka.util.Timeout


object Blocking {
  type ByteArray = Array[Byte]
  type PendingRefund = Option[Long]
  type CompleteRefund = Option[String]
  type OptionalUpdate = Option[String]

  type RepLong = Rep[Long]
  type RepByteArray = Rep[ByteArray]

  val span: FiniteDuration = 25.seconds
  implicit val timeout: Timeout = Timeout(span)

  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)

  def createTablesIfNotExist(db: Database): Unit = {
    val tables = Seq(Channels.model, Updates.model, Preimages.model).map(_.schema.createIfNotExists)
    val action = db.run(DBIO.sequence(tables).transactionally)
    Await.result(action, span)
  }
}


object Channels {
  final val tableName = "channels"
  val model = TableQuery[Channels]

  type DbType = (Long, ByteArray, Long, Int, Boolean, CompleteRefund, PendingRefund, Long, Long, ByteArray, ByteArray, Boolean)

  val insertCompiled = Compiled {
    for (x <- model) yield (x.remoteNodeId, x.shortChannelId, x.inFlightHtlcs, x.isHost, x.completeRefund, x.pendingRefund, x.lastBlockDay, x.createdAt, x.data, x.secret, x.isVisible)
  }

  val findByRemoteNodeIdUpdatableCompiled = Compiled {
    (nodeId: RepByteArray) => for (x <- model if x.remoteNodeId === nodeId) yield (x.inFlightHtlcs, x.isHost, x.completeRefund, x.pendingRefund, x.lastBlockDay, x.data, x.isVisible)
  }

  val findSecretUpdatableByRemoteNodeIdCompiled = Compiled { nodeId: RepByteArray => for (x <- model if x.remoteNodeId === nodeId) yield x.secret }

  val findIsVisibleUpdatableByRemoteNodeIdCompiled = Compiled { nodeId: RepByteArray => for (x <- model if x.remoteNodeId === nodeId) yield x.isVisible }

  val findBySecretCompiled = Compiled { secret: RepByteArray => for (x <- model if x.secret === secret) yield x.data }

  val listHotChannelsCompiled = Compiled { for (x <- model if x.inFlightHtlcs > 0 && x.isVisible) yield x.data }

  val listClientChannelsCompiled = Compiled { for (x <- model if !x.isHost && x.isVisible) yield x.data }
}

class Channels(tag: Tag) extends Table[Channels.DbType](tag, Channels.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  // These are not updatable
  def remoteNodeId: Rep[ByteArray] = column[ByteArray]("remote_node_id", O.Unique)
  def shortChannelId: Rep[Long] = column[Long]("short_channel_id", O.Unique)
  def createdAt: Rep[Long] = column[Long]("created_at_msec")
  // These get derived from data when updating
  def inFlightHtlcs: Rep[Int] = column[Int]("in_flight_htlcs")
  def isHost: Rep[Boolean] = column[Boolean]("is_host")
  def completeRefund: Rep[CompleteRefund] = column[CompleteRefund]("complete_refund")
  def pendingRefund: Rep[PendingRefund] = column[PendingRefund]("pending_refund")
  def lastBlockDay: Rep[Long] = column[Long]("last_block_day")
  // These have special update rules
  def data: Rep[ByteArray] = column[ByteArray]("data")
  def secret: Rep[ByteArray] = column[ByteArray]("secret")
  def isVisible: Rep[Boolean] = column[Boolean]("is_visible")

  def idx1: Index = index("channels__is_host__is_visible__idx", (isHost, isVisible), unique = false) // Select non-hosts on startup for automatic reconnect
  def idx2: Index = index("channels__in_flight_htlcs__is_visible__idx", (inFlightHtlcs, isVisible), unique = false) // Select these on startup for HTLC resolution
  def idx3: Index = index("channels__secret__idx", secret, unique = false) // Find these on user request

  def * = (id, remoteNodeId, shortChannelId, inFlightHtlcs, isHost, completeRefund, pendingRefund, lastBlockDay, createdAt, data, secret, isVisible)
}


object Updates {
  final val tableName = "updates"
  val model = TableQuery[Updates]

  type DbType = (Long, Long, String, OptionalUpdate, OptionalUpdate, Long, Long)

  val findAnnounceDeletableCompiled = Compiled {
    model.filter(x => x.channelUpdate1.isEmpty && x.channelUpdate2.isEmpty)
  }

  val findUpdate1stOldUpdatableCompiled = Compiled {
    (threshold: RepLong) => for (x <- model if x.update1Stamp < threshold) yield x.channelUpdate1
  }

  val findUpdate2ndOldUpdatableCompiled = Compiled {
    (threshold: RepLong) => for (x <- model if x.update2Stamp < threshold) yield x.channelUpdate2
  }

  def update1st(shortChannelId: Long, update: String, updateStamp: Long): SqlAction[Int, NoStream, Effect] = sqlu"""
    UPDATE #${Updates.tableName} SET channel_update_1_opt = $update, update_1_stamp_sec = $updateStamp
    WHERE short_channel_id = $shortChannelId
  """

  def update2nd(shortChannelId: Long, update: String, updateStamp: Long): SqlAction[Int, NoStream, Effect] = sqlu"""
    UPDATE #${Updates.tableName} SET channel_update_2_opt = $update, update_2_stamp_sec = $updateStamp
    WHERE short_channel_id = $shortChannelId
  """

  def insert(shortChannelId: Long, announce: String): SqlAction[Int, NoStream, Effect] = sqlu"""
    INSERT INTO #${Updates.tableName} (short_channel_id, channel_announce) VALUES ($shortChannelId, $announce)
    ON CONFLICT (short_channel_id) DO UPDATE SET channel_announce = $announce
  """
}

class Updates(tag: Tag) extends Table[Updates.DbType](tag, Updates.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def shortChannelId: Rep[Long] = column[Long]("short_channel_id", O.Unique)
  def channelAnnounce: Rep[String] = column[String]("channel_announce")
  def channelUpdate1: Rep[OptionalUpdate] = column[OptionalUpdate]("channel_update_1_opt", O Default None)
  def channelUpdate2: Rep[OptionalUpdate] = column[OptionalUpdate]("channel_update_2_opt", O Default None)
  def update1Stamp: Rep[Long] = column[Long]("update_1_stamp_sec", O Default 0L)
  def update2Stamp: Rep[Long] = column[Long]("update_2_stamp_sec", O Default 0L)

  def idx1: Index = index("updates__update_1_stamp_sec__idx", update1Stamp, unique = false)
  def idx2: Index = index("updates__update_2_stamp_sec__idx", update2Stamp, unique = false)
  def idx3: Index = index("updates__channel_update_1_opt__channel_update_2_opt__idx", (channelUpdate1, channelUpdate2), unique = false)

  def * = (id, shortChannelId, channelAnnounce, channelUpdate1, channelUpdate2, update1Stamp, update2Stamp)
}


object Preimages {
  final val tableName = "preimages"
  val model = TableQuery[Preimages]

  type DbType = (Long, ByteArray, ByteArray)

  val insertCompiled = Compiled {
    for (x <- model) yield (x.hash, x.preimage)
  }

  val findByHash = Compiled {
    (hash: RepByteArray) => model.filter(_.hash === hash).map(_.preimage)
  }
}

class Preimages(tag: Tag) extends Table[Preimages.DbType](tag, Preimages.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def hash: Rep[ByteArray] = column[ByteArray]("hash", O.Unique)
  def preimage: Rep[ByteArray] = column[ByteArray]("preimage")
  def * = (id, hash, preimage)
}