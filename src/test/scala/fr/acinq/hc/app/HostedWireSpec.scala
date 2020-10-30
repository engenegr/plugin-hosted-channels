package fr.acinq.hc.app

import java.util.UUID

import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{Channel, Origin}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.{AnnouncementSignatures, ChannelUpdate, Error, UpdateAddHtlc, UpdateFailHtlc}
import fr.acinq.hc.app.channel.{HC_DATA_ESTABLISHED, HostedCommitments, HostedState}
import fr.acinq.hc.app.wire._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.util.Random


object HostedWireSpec {
  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte): ByteVector32 = ByteVector32(bin(32, fill))

  val add1: UpdateAddHtlc = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = TestConstants.emptyOnionPacket)
  val add2: UpdateAddHtlc = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = TestConstants.emptyOnionPacket)

  val init_hosted_channel: InitHostedChannel = InitHostedChannel(UInt64(6), 10.msat, 20, 500000000L.msat, 5000, 1000000.sat, 1000000.msat)
  val lcss1: LastCrossSignedState = LastCrossSignedState(bin(47, 0), init_hosted_channel, 10000, 10000.msat, 20000.msat, 10, 20, List(add2, add1), List(add1, add2), randomBytes64, randomBytes64)

  val htlc1: IncomingHtlc = IncomingHtlc(add1)
  val htlc2: OutgoingHtlc = OutgoingHtlc(add2)
  val cs: CommitmentSpec = CommitmentSpec(
    htlcs = Set(htlc1, htlc2),
    feeratePerKw = FeeratePerKw(Satoshi(0L)),
    toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
  )

  val channelUpdate: ChannelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey,
    ShortChannelId(142553), CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

  val error: Error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

  val hdc: HostedCommitments = HostedCommitments(
    isHost = true,
    randomKey.publicKey,
    randomKey.publicKey,
    channelId = randomBytes32,
    localSpec = cs,
    originChannels = Map(42L -> Origin.LocalCold(UUID.randomUUID),
      15000L -> Origin.ChannelRelayedCold(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
    lcss1,
    futureUpdates = List(Right(add1), Left(add2)),
    timedOutToPeerHtlcLeftOverIds = Set.empty,
    fulfilledByPeerHtlcLeftOverIds = Set.empty,
    announceChannel = false)

  val data: HC_DATA_ESTABLISHED = HC_DATA_ESTABLISHED(
    hdc,
    localError = None,
    remoteError = Some(error),
    overrideProposal = None,
    refundPendingInfo = Some(RefundPending(System.currentTimeMillis / 1000)),
    refundCompleteInfo = Some("Has been refunded to address n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ"),
    channelUpdate)
}

class HostedWireSpec extends AnyFunSuite {
  import HostedWireSpec._

  test("Correctly derive HC id and short id") {
    val pubkey1 = randomKey.publicKey.value
    val pubkey2 = randomKey.publicKey.value
    assert(Tools.hostedChanId(pubkey1, pubkey2) === Tools.hostedChanId(pubkey2, pubkey1))
    assert(Tools.hostedShortChanId(pubkey1, pubkey2) === Tools.hostedShortChanId(pubkey2, pubkey1))
  }

  test("Encode and decode data") {
    val binary = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.encode(data).require
    val check = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.decodeValue(binary).require
    assert(data === check)
  }

  test("Encode and decode commitments") {
    {
      val binary = HostedChannelCodecs.hostedCommitmentsCodec.encode(hdc).require
      val check = HostedChannelCodecs.hostedCommitmentsCodec.decodeValue(binary).require
      assert(hdc.localSpec === check.localSpec)
      assert(hdc === check)
    }

    val state = HostedState(ByteVector32.Zeroes, List(add1, add2), List.empty, lcss1)

    {
      val binary = HostedChannelCodecs.hostedStateCodec.encode(state).require
      val check = HostedChannelCodecs.hostedStateCodec.decodeValue(binary).require
      assert(state === check)
    }
  }

  test("Encode and decode messages") {
    import HostedWireSpec._
    assert(Codecs.toUnknownHostedMessage(lcss1).tag === HC.HC_LAST_CROSS_SIGNED_STATE_TAG)
    assert(Codecs.decodeHostedMessage(Codecs.toUnknownHostedMessage(lcss1)).require === lcss1)
  }

  test("Encode and decode routing messages") {
    def sig: ByteVector64 = Crypto.sign(randomBytes32, randomKey)
    val a: Crypto.PrivateKey = randomKey
    val b: Crypto.PrivateKey = randomKey

    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)

    assert(Codecs.toUnknownAnnounceMessage(channel, isGossip = true).tag === HC.PHC_ANNOUNCE_GOSSIP_TAG)
    assert(Codecs.toUnknownAnnounceMessage(channel_update_1, isGossip = false).tag === HC.PHC_UPDATE_SYNC_TAG)
    assert(Codecs.decodeAnnounceMessage(Codecs.toUnknownAnnounceMessage(channel, isGossip = true)).require === channel)
    assert(Codecs.decodeAnnounceMessage(Codecs.toUnknownAnnounceMessage(channel_update_2, isGossip = false)).require === channel_update_2)
  }

  test("Encode and decode standard messages with channel id") {
    def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)
    def bin32(fill: Byte) = ByteVector32(bin(32, fill))
    val update_fail_htlc = UpdateFailHtlc(randomBytes32, 2, bin(154, 0))
    val update_add_htlc = UpdateAddHtlc(randomBytes32, 2, 3.msat, bin32(0), CltvExpiry(4), TestConstants.emptyOnionPacket)
    val announcement_signatures = AnnouncementSignatures(randomBytes32, ShortChannelId(42), randomBytes64, randomBytes64)

    assert(Codecs.toUnknownHasChanIdMessage(update_fail_htlc).tag === HC.HC_UPDATE_FAIL_HTLC_TAG)
    assert(Codecs.toUnknownHasChanIdMessage(update_add_htlc).tag === HC.HC_UPDATE_ADD_HTLC_TAG)
    assert(Codecs.toUnknownHasChanIdMessage(announcement_signatures).tag === HC.HC_ANNOUNCEMENT_SIGNATURES_TAG)

    assert(Codecs.decodeHasChanIdMessage(Codecs.toUnknownHasChanIdMessage(update_fail_htlc)).require === update_fail_htlc)
    assert(Codecs.decodeHasChanIdMessage(Codecs.toUnknownHasChanIdMessage(update_add_htlc)).require === update_add_htlc)
    assert(Codecs.decodeHasChanIdMessage(Codecs.toUnknownHasChanIdMessage(announcement_signatures)).require === announcement_signatures)
  }
}
