package fr.acinq.hc.app

import fr.acinq.eclair._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import fr.acinq.bitcoin.{ByteVector32, Crypto, LexicographicalOrdering, Protocol}
import fr.acinq.eclair.wire.{AnnouncementMessage, Color, HasChannelId, UnknownMessage}
import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}

import fr.acinq.eclair.channel.Channel.OutgoingMessage
import net.ceedubs.ficus.readers.ValueReader
import fr.acinq.bitcoin.Crypto.PublicKey
import com.typesafe.config.ConfigFactory
import org.postgresql.util.PSQLException
import fr.acinq.eclair.io.PeerConnected
import fr.acinq.hc.app.wire.Codecs
import slick.jdbc.PostgresProfile
import scodec.bits.ByteVector
import java.nio.ByteOrder
import scala.util.Try


object Tools {
  def toMapBy[K, V](items: Iterable[V])(mapper: V => K): Map[K, V] = items.map(item => mapper(item) -> item).toMap
  case object DuplicateShortId extends Throwable("Duplicate ShortId is not allowed here")

  abstract class DuplicateHandler[T] { me =>
    def execute(data: T): Try[Boolean] = Try(me insert data) recover {
      case dup: PSQLException if "23505" == dup.getSQLState => throw DuplicateShortId
      case otherError: Throwable => throw otherError
    }

    def insert(data: T): Boolean
  }

  // HC ids derivation

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector32 = {
    val nodesCombined = hostedNodesCombined(pubkey1, pubkey2)
    Crypto.sha256(nodesCombined)
  }

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector): ShortChannelId = {
    val stream = new ByteArrayInputStream(hostedNodesCombined(pubkey1, pubkey2).toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    val id = List.fill(8)(getChunk).foldLeft(Long.MaxValue)(_ % _)
    ShortChannelId(id)
  }
}

case class PeerConnectedWrap(info: PeerConnected) { me =>
  def sendHasChannelIdMsg(message: HasChannelId): Unit = me sendUnknownMsg Codecs.toUnknownHasChanIdMessage(message)
  def sendHostedChannelMsg(message: HostedChannelMessage): Unit = me sendUnknownMsg Codecs.toUnknownHostedMessage(message)
  def sendRoutingMsg(message: AnnouncementMessage): Unit = me sendUnknownMsg Codecs.toUnknownAnnounceMessage(message, isGossip = true)
  def sendUnknownMsg(message: UnknownMessage): Unit = info.peer ! OutgoingMessage(message, info.connectionInfo.peerConnection)
  lazy val remoteIp: Array[Byte] = info.connectionInfo.address.getAddress.getAddress
}


object Config {
  private val config = ConfigFactory parseFile new File(s"${System getProperty "user.dir"}/src/main/resources", "hc.conf")

  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)

  implicit val colorReader: ValueReader[Color] = ValueReader.relative { source =>
    Color(source.getInt("r").toByte, source.getInt("g").toByte, source.getInt("b").toByte)
  }

  val vals: Vals = config.as[Vals]("config.vals")
}


case class HCParams(feeBaseMsat: Long, feeProportionalMillionths: Long, cltvDeltaBlocks: Int, onChainRefundThresholdSat: Long,
                    liabilityDeadlineBlockdays: Int, defaultCapacityMsat: Long, maxHtlcValueInFlightMsat: Long,
                    htlcMinimumMsat: Long, maxAcceptedHtlcs: Int) {

  val feeBase: MilliSatoshi = MilliSatoshi(feeBaseMsat)

  val cltvDelta: CltvExpiryDelta = CltvExpiryDelta(cltvDeltaBlocks)

  val initMsg: InitHostedChannel =
    InitHostedChannel(UInt64(maxHtlcValueInFlightMsat), htlcMinimumMsat.msat, maxAcceptedHtlcs,
      defaultCapacityMsat.msat, liabilityDeadlineBlockdays, onChainRefundThresholdSat.sat,
      initialClientBalanceMsat = 0L.msat)
}

case class HCOverrideParams(nodeId: String, params: HCParams)

case class Branding(logo: String, color: Color) {
  var brandingMessageOpt: Option[HostedChannelBranding] = None

  Try {
    val pngBytes = ByteVector view Files.readAllBytes(Paths get logo)
    val message = HostedChannelBranding(color, pngBytes)
    brandingMessageOpt = Some(message)
  }
}

case class PHCConfig(maxPerNode: Long, minNormalChans: Long, maxSyncSendsPerIpPerMinute: Int) {
  val capacity: MilliSatoshi = MilliSatoshi(100000000000000L) // Always exactly 1000 BTC
}

case class Vals(hcDefaultParams: HCParams, hcOverrideParams: List[HCOverrideParams],
                maxNewChansPerIpPerHour: Int, branding: Branding, phcConfig: PHCConfig) {

  val hcOverrideMap: Map[PublicKey, HCOverrideParams] =
    Tools.toMapBy[PublicKey, HCOverrideParams](hcOverrideParams) {
      hcParams => PublicKey(ByteVector fromValidHex hcParams.nodeId)
    }
}