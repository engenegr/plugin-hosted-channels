package fr.acinq.hc.app.dbo

import scala.concurrent.duration._
import fr.acinq.hc.app.dbo.Blocking._
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Index, Tag}

import slick.jdbc.PostgresProfile.backend.Database
import scala.concurrent.Await
import akka.util.Timeout
import slick.dbio.Effect


object Blocking {
  type ByteArray = Array[Byte]
  type PendingRefund = Option[Long]
  type CompleteRefund = Option[String]

  type RepInt = Rep[Int]
  type RepLong = Rep[Long]
  type RepString = Rep[String]
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