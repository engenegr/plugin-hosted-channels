package fr.acinq.hc.app

import fr.acinq.bitcoin.{ByteVector32, Crypto, LexicographicalOrdering, Protocol}
import com.typesafe.config.{Config => Configuration}
import java.io.{ByteArrayInputStream, File}

import com.google.common.cache.CacheBuilder
import com.typesafe.config.ConfigFactory
import org.postgresql.util.PSQLException
import fr.acinq.eclair.ShortChannelId
import java.util.concurrent.TimeUnit
import slick.jdbc.PostgresProfile
import scodec.bits.ByteVector
import java.nio.ByteOrder
import scala.util.Try


object Tools {
  def makeExpireAfterAccessCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterAccess(expiryMins, TimeUnit.MINUTES)
  def makeExpireAfterWriteCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterWrite(expiryMins, TimeUnit.MINUTES)
  case object DuplicateShortId extends Throwable("Duplicate ShortId is not allowed here")

  abstract class DuplicateHandler[T] { me =>
    def execute(data: T): Try[Boolean] = Try(me firstMove data) recover {
      case dup: PSQLException if "23505" == dup.getSQLState => me secondMove data
      case otherDatabaseError: Throwable => throw otherDatabaseError
    }

    def firstMove(data: T): Boolean
    def secondMove(data: T): Boolean
  }

  // HC ids derivation

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector32 =
    Crypto sha256 hostedNodesCombined(pubkey1, pubkey2)

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector): ShortChannelId = {
    val stream = new ByteArrayInputStream(hostedNodesCombined(pubkey1, pubkey2).toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    val id = List.fill(8)(getChunk).foldLeft(Long.MaxValue)(_ % _)
    ShortChannelId(id)
  }
}

object Config {
  val config: Configuration = ConfigFactory parseFile new File(s"${System getProperty "user.dir"}/src/main/resources", "hc.conf")
  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)
}