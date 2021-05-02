package fr.acinq.hc.app

import fr.acinq.eclair._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import fr.acinq.eclair.wire.{AnnouncementMessage, ChannelAnnouncement, ChannelUpdate, Color, HasChannelId, UnknownMessage}
import fr.acinq.bitcoin.{ByteVector32, Crypto, LexicographicalOrdering, Protocol, Satoshi, SatoshiLong}
import fr.acinq.hc.app.channel.{HostedChannelVersion, HostedCommitments}
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}

import fr.acinq.eclair.channel.Channel.OutgoingMessage
import fr.acinq.eclair.channel.ChannelVersion
import net.ceedubs.ficus.readers.ValueReader
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import org.postgresql.util.PSQLException
import fr.acinq.eclair.io.PeerConnected
import fr.acinq.hc.app.wire.Codecs
import slick.jdbc.PostgresProfile
import scodec.bits.ByteVector
import java.nio.ByteOrder
import scala.util.Try


object Tools {
  def none: PartialFunction[Any, Unit] = { case _ => }

  case object DuplicateShortId extends Throwable("Duplicate ShortId is not allowed here")

  abstract class DuplicateHandler[T] { self =>
    def execute(data: T): Try[Boolean] = Try(self insert data) recover {
      case dup: PSQLException if "23505" == dup.getSQLState => throw DuplicateShortId
      case otherError: Throwable => throw otherError
    }

    def insert(data: T): Boolean
  }

  def makePHCAnnouncementSignature(nodeParams: NodeParams, cs: HostedCommitments, shortChannelId: ShortChannelId, wantsReply: Boolean): AnnouncementSignature = {
    val witness = Announcements.generateChannelAnnouncementWitness(nodeParams.chainHash, shortChannelId, nodeParams.nodeId, cs.remoteNodeId, nodeParams.nodeId, cs.remoteNodeId, Features.empty)
    AnnouncementSignature(nodeParams.nodeKeyManager.signChannelAnnouncement(witness), wantsReply)
  }

  def makePHCAnnouncement(nodeParams: NodeParams, ls: AnnouncementSignature, rs: AnnouncementSignature, shortChannelId: ShortChannelId, remoteNodeId: PublicKey): ChannelAnnouncement =
    Announcements.makeChannelAnnouncement(nodeParams.chainHash, shortChannelId, nodeParams.nodeId, remoteNodeId, nodeParams.nodeId, remoteNodeId, ls.nodeSignature, rs.nodeSignature, ls.nodeSignature, rs.nodeSignature)

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

trait PeerConnectedWrap {
  def sendHasChannelIdMsg(message: HasChannelId): Unit
  def sendHostedChannelMsg(message: HostedChannelMessage): Unit
  def sendRoutingMsg(message: AnnouncementMessage): Unit
  def sendUnknownMsg(message: UnknownMessage): Unit
  def remoteIp: Array[Byte]
  def info: PeerConnected
}

case class PeerConnectedWrapNormal(info: PeerConnected) extends PeerConnectedWrap { me =>
  def sendHasChannelIdMsg(message: HasChannelId): Unit = me sendUnknownMsg Codecs.toUnknownHasChanIdMessage(message)
  def sendHostedChannelMsg(message: HostedChannelMessage): Unit = me sendUnknownMsg Codecs.toUnknownHostedMessage(message)
  def sendRoutingMsg(message: AnnouncementMessage): Unit = me sendUnknownMsg Codecs.toUnknownAnnounceMessage(message, isGossip = true)
  def sendUnknownMsg(message: UnknownMessage): Unit = info.peer ! OutgoingMessage(message, info.connectionInfo.peerConnection)
  lazy val remoteIp: Array[Byte] = info.connectionInfo.address.getAddress.getAddress
}


object Config {
  implicit val colorReader: ValueReader[Color] = ValueReader.relative { source =>
    Color(source.getInt("r").toByte, source.getInt("g").toByte, source.getInt("b").toByte)
  }

  val resourcesDir: String = s"${System getProperty "user.dir"}/plugin-resources/hosted-channels"

  val config: TypesafeConfig = ConfigFactory parseFile new File(resourcesDir, "hc.conf")

  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)

  val attemptCreateTables: Boolean = config.as[Boolean]("config.attemptCreateTables")

  val vals: Vals = config.as[Vals]("config.vals")
}


case class HCParams(feeBaseMsat: Long, feeProportionalMillionths: Long,
                    cltvDeltaBlocks: Int, onChainRefundThresholdSat: Long,
                    liabilityDeadlineBlockdays: Int, channelCapacityMsat: Long,
                    htlcMinimumMsat: Long, maxAcceptedHtlcs: Int, isResizable: Boolean) {

  val feeBase: MilliSatoshi = feeBaseMsat.msat

  val htlcMinimum: MilliSatoshi = htlcMinimumMsat.msat

  val onChainRefundThreshold: Satoshi = onChainRefundThresholdSat.sat

  val channelVersion: ChannelVersion = if (isResizable) HostedChannelVersion.RESIZABLE else ChannelVersion.STANDARD

  val initMsg: InitHostedChannel =
    InitHostedChannel(UInt64(channelCapacityMsat), htlcMinimum, maxAcceptedHtlcs, channelCapacityMsat.msat,
      liabilityDeadlineBlockdays, onChainRefundThreshold, initialClientBalanceMsat = 0L.msat, channelVersion)

  def areDifferent(cu: ChannelUpdate): Boolean =
    cu.cltvExpiryDelta.toInt != cltvDeltaBlocks || !cu.htlcMaximumMsat.contains(htlcMinimum) ||
      cu.feeBaseMsat != feeBase || cu.feeProportionalMillionths != feeProportionalMillionths
}

case class HCOverrideParams(nodeId: String, params: HCParams)

case class Branding(logo: String, background: String, color: Color, contactInfo: String, enabled: Boolean) {
  lazy val brandingMessage: HostedChannelBranding = HostedChannelBranding(color, logoBytes.toOption, backgroundBytes.toOption, contactInfo)

  var logoBytes: Try[ByteVector] = Try {
    val pngBytes = Paths.get(s"${Config.resourcesDir}/$logo")
    ByteVector(Files readAllBytes pngBytes)
  }

  var backgroundBytes: Try[ByteVector] = Try {
    val pngBytes = Paths.get(s"${Config.resourcesDir}/$background")
    ByteVector(Files readAllBytes pngBytes)
  }
}

case class PHCConfig(maxPerNode: Long, minNormalChans: Long, maxSyncSendsPerIpPerMinute: Int) {
  val maxCapacity: MilliSatoshi = MilliSatoshi(1000000000000000L) // No more than 10 000 BTC

  val minCapacity: MilliSatoshi = MilliSatoshi(50000000000L) // At least 0.5 BTC
}

case class ApiParams(password: String, bindingIp: String, port: Int)

case class Vals(hcDefaultParams: HCParams, hcOverrideParams: List[HCOverrideParams],
                maxNewChansPerIpPerHour: Int, maxPreimageRequestsPerIpPerMinute: Int,
                branding: Branding, phcConfig: PHCConfig, apiParams: ApiParams) {

  val hcOverrideMap: Map[PublicKey, HCOverrideParams] = hcOverrideParams.map { hcParams =>
    val nodeKey = ByteVector.fromValidHex(hcParams.nodeId)
    (PublicKey(nodeKey), hcParams)
  }.toMap
}