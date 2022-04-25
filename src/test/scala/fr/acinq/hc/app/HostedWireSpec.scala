package fr.acinq.hc.app

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{Channel, Origin}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version3.{HCProtocolCodecs, HostedChannelCodecs}
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, Error, UpdateAddHtlc, UpdateFailHtlc}
import fr.acinq.hc.app.channel._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{BitVector, ByteVector}

import java.util.UUID
import scala.util.Random


object HostedWireSpec {
  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)
  def sig: ByteVector64 = Crypto.sign(randomBytes32, randomKey)
  def bin32(fill: Byte): ByteVector32 = ByteVector32(bin(32, fill))

  val add1: UpdateAddHtlc = UpdateAddHtlc(
    channelId = ByteVector32.One,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = ByteVector32.Zeroes,
    onionRoutingPacket = TestConstants.emptyOnionPacket)

  val add2: UpdateAddHtlc = UpdateAddHtlc(
    channelId = ByteVector32.One,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = ByteVector32.Zeroes,
    onionRoutingPacket = TestConstants.emptyOnionPacket)

  val invoke_hosted_channel: InvokeHostedChannel = InvokeHostedChannel(Block.LivenetGenesisBlock.hash, ByteVector.fromValidHex("00" * 32), secret = ByteVector.fromValidHex("00" * 32))

  val init_hosted_channel: InitHostedChannel = InitHostedChannel(UInt64(6), 10.msat, 20, 500000000L.msat, 1000000.msat, List(HCFeature.mandatory, ResizeableHCFeature.mandatory))

  val state_update: StateUpdate = StateUpdate(blockDay = 20020L, localUpdates = 1202L, remoteUpdates = 10L, ByteVector64.Zeroes)

  val last_cross_signed_state_1: LastCrossSignedState = LastCrossSignedState(isHost = true, bin(47, 0), init_hosted_channel, 10000, 10000.msat, 20000.msat, 10, 20,
    List(add2, add1), List(add1, add2), ByteVector64.Zeroes, ByteVector64.Zeroes)

  val htlc1: IncomingHtlc = IncomingHtlc(add1)
  val htlc2: OutgoingHtlc = OutgoingHtlc(add2)
  val cs: CommitmentSpec = CommitmentSpec(htlcs = Set(htlc1, htlc2), FeeratePerKw(Satoshi(0L)), toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)), toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue)))

  val channelUpdate: ChannelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey,
    ShortChannelId(142553), CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

  val error: Error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

  val localNodeId: Crypto.PublicKey = randomKey.publicKey

  val hdc: HostedCommitments = HostedCommitments(localNodeId, randomKey.publicKey, channelId = randomBytes32, localSpec = cs,
    originChannels = Map(42L -> Origin.LocalCold(UUID.randomUUID), 15000L -> Origin.ChannelRelayedCold(ByteVector32(ByteVector.fill(32)(42)), 43,
      MilliSatoshi(11000000L), MilliSatoshi(10000000L))), last_cross_signed_state_1, nextLocalUpdates = List(add1, add2), nextRemoteUpdates = Nil, announceChannel = false)

  val data: HC_DATA_ESTABLISHED = HC_DATA_ESTABLISHED(hdc, channelUpdate, localErrors = Nil, remoteError = Some(ErrorExt generateFrom error), overrideProposal = None)
}

class HostedWireSpec extends AnyFunSuite {
  import HostedWireSpec._

  test("Correct feature") {
    assert(HCFeature.plugin.bitIndex == HCFeature.optional)
  }

  test("Correctly derive HC id and short id") {
    val pubkey1 = randomKey.publicKey.value
    val pubkey2 = randomKey.publicKey.value
    assert(Tools.hostedChanId(pubkey1, pubkey2) == Tools.hostedChanId(pubkey2, pubkey1))
    assert(Tools.hostedShortChanId(pubkey1, pubkey2) == Tools.hostedShortChanId(pubkey2, pubkey1))
  }

  test("Encode and decode data") {
    val binary = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.encode(data).require
    val check = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.decodeValue(binary).require
    assert(data == check)
    val a: Crypto.PrivateKey = randomKey
    val b: Crypto.PrivateKey = randomKey
    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, randomKey.publicKey,
      randomKey.publicKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val data1 = data.copy(channelAnnouncement = Some(channel))
    val binary1 = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.encode(data1).require
    val check1 = HostedChannelCodecs.HC_DATA_ESTABLISHED_Codec.decodeValue(binary1).require
    assert(data1 == check1)
  }

  test("Encode and decode commitments") {
    {
      val binary = HostedChannelCodecs.hostedCommitmentsCodec.encode(hdc).require
      val check = HostedChannelCodecs.hostedCommitmentsCodec.decodeValue(binary).require
      assert(hdc.localSpec == check.localSpec)
      assert(hdc == check)
    }

    val state = HostedState(randomKey.publicKey, randomKey.publicKey, last_cross_signed_state_1)

    {
      val binary = HostedChannelCodecs.hostedStateCodec.encode(state).require
      val check = HostedChannelCodecs.hostedStateCodec.decodeValue(binary).require
      assert(state == check)
    }
  }

  test("Encode and decode messages") {
    import HostedWireSpec._
    val unknown = HCProtocolCodecs.toUnknownHostedMessage(last_cross_signed_state_1)
    assert(unknown.tag == HC.HC_LAST_CROSS_SIGNED_STATE_TAG)
    assert(HCProtocolCodecs.decodeHostedMessage(unknown).require == last_cross_signed_state_1)
  }

  test("Encode and decode routing messages") {
    val a: Crypto.PrivateKey = randomKey
    val b: Crypto.PrivateKey = randomKey

    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)

    assert(HCProtocolCodecs.toUnknownAnnounceMessage(channel, isGossip = true).tag == HC.PHC_ANNOUNCE_GOSSIP_TAG)
    assert(HCProtocolCodecs.toUnknownAnnounceMessage(channel_update_1, isGossip = false).tag == HC.PHC_UPDATE_SYNC_TAG)
    assert(HCProtocolCodecs.decodeAnnounceMessage(HCProtocolCodecs.toUnknownAnnounceMessage(channel, isGossip = true)).require == channel)
    assert(HCProtocolCodecs.decodeAnnounceMessage(HCProtocolCodecs.toUnknownAnnounceMessage(channel_update_2, isGossip = false)).require == channel_update_2)
  }

  test("Encode and decode standard messages with channel id") {
    def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)
    def bin32(fill: Byte) = ByteVector32(bin(32, fill))
    val update_fail_htlc = UpdateFailHtlc(randomBytes32, 2, bin(154, 0))
    val update_add_htlc = UpdateAddHtlc(randomBytes32, 2, 3.msat, bin32(0), CltvExpiry(4), TestConstants.emptyOnionPacket)
    val announcement_signature = AnnouncementSignature(randomBytes64, wantsReply = false)

    assert(HCProtocolCodecs.toUnknownHasChanIdMessage(update_fail_htlc).tag == HC.HC_UPDATE_FAIL_HTLC_TAG)
    assert(HCProtocolCodecs.toUnknownHasChanIdMessage(update_add_htlc).tag == HC.HC_UPDATE_ADD_HTLC_TAG)
    assert(HCProtocolCodecs.toUnknownHostedMessage(announcement_signature).tag == HC.HC_ANNOUNCEMENT_SIGNATURE_TAG)

    assert(HCProtocolCodecs.decodeHasChanIdMessage(HCProtocolCodecs.toUnknownHasChanIdMessage(update_fail_htlc)).require == update_fail_htlc)
    assert(HCProtocolCodecs.decodeHasChanIdMessage(HCProtocolCodecs.toUnknownHasChanIdMessage(update_add_htlc)).require == update_add_htlc)
    assert(HCProtocolCodecs.decodeHostedMessage(HCProtocolCodecs.toUnknownHostedMessage(announcement_signature)).require == announcement_signature)
  }

  test("Encode and decode an Error")  {
    val error1 = Error(randomBytes32, ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG)
    val error2 = Error(randomBytes32, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED + "message")

    assert(HCProtocolCodecs.toUnknownHasChanIdMessage(error1).tag == HC.HC_ERROR_TAG)
    assert(HCProtocolCodecs.toUnknownHasChanIdMessage(error2).tag == HC.HC_ERROR_TAG)

    assert(HCProtocolCodecs.decodeHasChanIdMessage(HCProtocolCodecs.toUnknownHasChanIdMessage(error1)).require == error1)
    assert(HCProtocolCodecs.decodeHasChanIdMessage(HCProtocolCodecs.toUnknownHasChanIdMessage(error2)).require == error2)

    assert(ErrorExt.extractDescription(error1) == "hosted-code=ERR_HOSTED_WRONG_REMOTE_SIG")
    assert(ErrorExt.extractDescription(error2) == "hosted-code=ERR_HOSTED_CHANNEL_DENIED, extra=message")
  }

  test("Preserve TLV data") {
    val updateAdd = "b908c0edb4b1b92b8a1d01e6c1084ce961f1fac683b05255c0807f802fe039cb00000000000003e800000000000f42400000000000000000000000000000000000000000000000000000000000000000000000900002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866196ef84350c2a76fc232b5d46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6101810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bf74b2ce49922898e9353fa268086c00ae8b7f718405b72ad3829dbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf776b75ebb389bf84d0bfbf58590e510e034572a01e409c309396778760423a8d8754c52e9a01a8f0e271cba5068bab5ee5bd0b5cd98276b0e04d60ba6a0f6bafd75ff41903ab352a1f47586eae3c6c8e437d4308766f71052b46ba2efbd87c0a781e8b3f456300fc7efbefc78ab515338666aed2070e674143c30b520b9cc1782ba8b46454db0d4ce72589cfc2eafb2db452ec98573ad08496483741de5376bfc7357fc6ea629e31236ba6ba7703014959129141a1719788ec83884f2e9151a680e2a96d2bcc67a8a2935aa11acee1f9d04812045b4ae5491220313756b5b9a0a6f867f2a95be1fab14870f04eeab694d9594620632b14ec4b424b495914f3dc587f75cd4582c113bb61e34a0fa7f79f97463be4e3c6fb99516889ed020acee419bb173d38e5ba18a00065e11fd733cf9ae46505dbb4ef70ef2f502601f4f6ee1fdb9" +
      "d17435e15080e962f24760843f35bac1ac079b694ff7c347c1ed6a87f02b0758fbf00917764716c68ed7d6e6c0e75ccdb6dc7fa59554784b3ad906127ea77a6cdd814662ee7d57a939e28d77b3da47efc072436a3fd7f9c40515af8c4903764301e62b57153a5ca03ff5bb49c7dc8d3b2858100fb4aa5df7a94a271b73a76129445a3ea180d84d19029c003c164db926ed6983e5219028721a294f145e3fcc20915b8a2147efc8b5d508339f64970feee3e2da9b9c9348c1a0a4df7527d0ae3f8ae507a5beb5c73c2016ecf387a3cd8b79df80a8e9412e707cb9c761a0809a84c606a779567f9f0edf685b38c98877e90d02aedd096ed841e50abf2114ce01efbff04788fb280f870eca20c7ec353d5c381903e7d08fc57695fd79c27d43e7bd603a876068d3f1c7f45af99003e5eec7e8d8c91e395320f1fc421ef3552ea033129429383304b760c8f93de342417c3223c2112a623c3514480cdfae8ec15a99abfca71b03a8396f19edc3d5000bcfb77b5544813476b1b521345f4da396db09e783870b97bc2034bd11611db30ed2514438b046f1eb7093eceddfb1e73880786cd7b540a3896eaadd0a0692e4b19439815b5f2ec855ec8ececce889442a64037e956452a3f7b86cb3780b3e316c8dde464bc74a60a85b613f849eb0b29daf81892877bd4be9ba5997fc35544d3c2a00e5e1f45dc925607d952c6a89721bd0b6f6aec03314d667166a5b8b18471403be7018b2479aaef6c7c6c554a50a98b717dff06d50be39fb36dc03e678e0a52fc615be" +
      "46b223e3bee83fa0c7c47a1f29fb94f1e9eebf6c9ecf8fc79ae847df2effb60d07aba301fc536546ec4899eedb4fec9a9bed79e3a83c4b32757745778e977e485c67c0f12bbc82c0b3bb0f4df0bd13d046fed4446f54cd85bfce55ef781a80e5f63d289d08de001237928c2a4e0c8694d0c1e68cc23f2409f30009019085e831a928e7bc5b00a1f29d25482f7fd0b6dad30e6ef8edc68ddf7db404ea7d11540fc2cee74863d64af4c945457e04b7bea0a5fb8636edadb1e1d6f2630d61062b781c1821f46eddadf269ea1fada829547590081b16bc116e074cae0224a375f2d9ce16e836687c89cd285e3b40f1e59ce2caa3d1d8cf37ee4d5e3abe7ef0afd6ffeb4fd6905677b950894863c828ab8d93519566f69fa3c2129da763bf58d9c4d2837d4d9e13821258f7e7098b34f695a589bd9eb568ba51ee3014b2d3ba1d4cf9ebaed0231ed57ecea7bd918216fef60b277740001f9608d72318982989930dcace320ce2dc700811debfae1dec177f4571015d87b4f01b25a9b2af1ef3ee509aab0de923a4791f595662c2b6c7198a681c92cc"

    assert(fr.acinq.eclair.wire.protocol.LightningMessageCodecs.updateAddHtlcCodec.decode(BitVector.fromValidHex(updateAdd)).require.value.tlvStream.unknown.head.tag == UInt64(4127926135L))
  }

  test("Test vectors") {
    val invoke_hosted_channel_raw = HCProtocolCodecs.invokeHostedChannelCodec.encode(invoke_hosted_channel).require.toHex
    assert(invoke_hosted_channel_raw == s"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000002000000000000000000000000000000000000000000000000000000000000000000020" +
      s"0000000000000000000000000000000000000000000000000000000000000000")

    val init_hosted_channel_raw = HCProtocolCodecs.initHostedChannelCodec.encode(init_hosted_channel).require.toHex
    assert(init_hosted_channel_raw == s"0000000000000006000000000000000a0014000000001dcd650000000000000f4240000280cc80ce")

    val state_update_raw = HCProtocolCodecs.stateUpdateCodec.encode(state_update).require.toHex
    assert(state_update_raw == s"00004e34000004b20000000a00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
  }
}
