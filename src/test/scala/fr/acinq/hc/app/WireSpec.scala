package fr.acinq.hc.app

import java.util.UUID

import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{Channel, ChannelVersion, Origin}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.{ChannelUpdate, Error, UpdateAddHtlc}
import fr.acinq.hc.app.channel.{HOSTED_DATA_COMMITMENTS, HostedState}
import fr.acinq.hc.app.wire.HostedChannelCodecs
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.util.Random


object WireSpec {
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

  val hdc: HOSTED_DATA_COMMITMENTS = HOSTED_DATA_COMMITMENTS(
    localNodeId = randomKey.publicKey,
    remoteNodeId = randomKey.publicKey,
    channelVersion = ChannelVersion.ZEROES,
    lastCrossSignedState = lcss1,
    futureUpdates = List(Right(add1), Left(add2)),
    originChannels = Map(42L -> Origin.LocalCold(UUID.randomUUID), 15000L -> Origin.ChannelRelayedCold(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
    localSpec = cs,
    isHost = true,
    channelUpdate = channelUpdate,
    localError = None,
    remoteError = Some(error),
    failedToPeerHtlcLeftoverIds = Set.empty,
    fulfilledByPeerHtlcLeftoverIds = Set(1, 2, 10000),
    overrideProposal = None,
    refundPendingInfo = Some(RefundPending(System.currentTimeMillis / 1000)),
    refundCompleteInfo = Some("Has been refunded to address n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ"),
    announceChannel = false)
}

class WireSpec extends AnyFunSuite {
  test("Correctly derive HC id and short id") {
    val pubkey1 = randomKey.publicKey.value
    val pubkey2 = randomKey.publicKey.value
    assert(Tools.hostedChanId(pubkey1, pubkey2) === Tools.hostedChanId(pubkey2, pubkey1))
    assert(Tools.hostedShortChanId(pubkey1, pubkey2) === Tools.hostedShortChanId(pubkey2, pubkey1))
  }

  test("Encode and decode commitments") {
    import WireSpec._

    {
      val binary = HostedChannelCodecs.HOSTED_DATA_COMMITMENTSCodec.encode(hdc).require
      val check = HostedChannelCodecs.HOSTED_DATA_COMMITMENTSCodec.decodeValue(binary).require
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
}
