package fr.acinq.hc.app

import java.util.UUID

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{ChannelUpdate, UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.hc.app.channel.{HC_DATA_ESTABLISHED, HostedChannelVersion, HostedCommitments}
import org.scalatest.funsuite.AnyFunSuite


class HostedChannelTypesSpec extends AnyFunSuite {
  val alicePrivKey: Crypto.PrivateKey = randomKey
  val bobPrivKey: Crypto.PrivateKey = randomKey

  val channelId: ByteVector32 = randomBytes32

  val initHostedChannel: InitHostedChannel = InitHostedChannel(maxHtlcValueInFlightMsat = UInt64(90000L),
    htlcMinimumMsat = 10.msat, maxAcceptedHtlcs = 3, 1000000L.msat, initialClientBalanceMsat = 0.msat, ChannelVersion.STANDARD)

  val preimage1: ByteVector32 = randomBytes32
  val preimage2: ByteVector32 = randomBytes32
  val updateAddHtlc1: UpdateAddHtlc = UpdateAddHtlc(channelId, 102, 10000.msat, Crypto.sha256(preimage1), CltvExpiry(4), TestConstants.emptyOnionPacket)
  val updateAddHtlc2: UpdateAddHtlc = UpdateAddHtlc(channelId, 103, 20000.msat, Crypto.sha256(preimage2), CltvExpiry(40), TestConstants.emptyOnionPacket)

  val lcss: LastCrossSignedState = LastCrossSignedState(isHost = true, refundScriptPubKey = randomBytes(119), initHostedChannel, blockDay = 100, localBalanceMsat = 100000.msat, remoteBalanceMsat = 900000.msat,
    localUpdates = 201, remoteUpdates = 101, incomingHtlcs = List(updateAddHtlc1, updateAddHtlc2), outgoingHtlcs = List(updateAddHtlc2, updateAddHtlc1),
    remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)

  val lcss1: LastCrossSignedState = lcss.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil)

  val localCommitmentSpec: CommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(0L.sat), lcss1.localBalanceMsat, lcss1.remoteBalanceMsat)

  val channelUpdate: ChannelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, CltvExpiryDelta(3), 4.msat, 5.msat, 6, None)

  test("HC version") {
    assert(!ChannelVersion.STANDARD.isSet(HostedChannelVersion.USE_RESIZE))
    assert(HostedChannelVersion.RESIZABLE.isSet(HostedChannelVersion.USE_RESIZE))
  }

  test("LCSS has the same sigHash for different order of in-flight HTLCs") {
    val lcssDifferentHtlcOrder = lcss.copy(incomingHtlcs = List(updateAddHtlc2, updateAddHtlc1), outgoingHtlcs = List(updateAddHtlc1, updateAddHtlc2))
    assert(lcss.hostedSigHash === lcssDifferentHtlcOrder.hostedSigHash)
  }

  test("Meddled LCSS has a different hash") {
    assert(lcss.hostedSigHash != lcss.copy(localUpdates = 200).hostedSigHash)
  }

  test("LCSS reversed twice is the same as original") {
    assert(lcss.reverse.reverse === lcss)
  }

  test("LCSS signature checks 1") {
    val aliceLocallySignedLCSS = lcss.withLocalSigOfRemote(alicePrivKey)
    val bobLocallySignedLCSS = lcss.reverse.withLocalSigOfRemote(bobPrivKey)
    val aliceFullySignedLCSS = aliceLocallySignedLCSS.copy(remoteSigOfLocal = bobLocallySignedLCSS.localSigOfRemote)
    val bobFullySignedLCSS = bobLocallySignedLCSS.copy(remoteSigOfLocal = aliceLocallySignedLCSS.localSigOfRemote)
    assert(aliceFullySignedLCSS.stateUpdate.localUpdates === bobFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.stateUpdate.localUpdates === aliceFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.verifyRemoteSig(alicePrivKey.publicKey))
    assert(aliceFullySignedLCSS.verifyRemoteSig(bobPrivKey.publicKey))
  }

  test("LCSS signature checks 2") {
    val aliceLocallySignedLCSS = lcss.withLocalSigOfRemote(alicePrivKey)
    val bobLocallySignedLCSS = lcss.reverse.withLocalSigOfRemote(bobPrivKey)
    assert(aliceLocallySignedLCSS.reverse.verifyRemoteSig(alicePrivKey.publicKey)) // Bob verifies Alice remote sig of Bob local view of LCSS
    assert(bobLocallySignedLCSS.reverse.verifyRemoteSig(bobPrivKey.publicKey)) // Alice verifies Bob remote sig of Alice local view of LCSS
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long): (ByteVector32, CMD_ADD_HTLC) = {
    val payment_preimage: ByteVector32 = randomBytes32
    val payment_hash: ByteVector32 = Crypto.sha256(payment_preimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(null, OutgoingPacket.Upstream.Local(UUID.randomUUID), payment_hash,
      ChannelHop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (payment_preimage, cmd)
  }

  private val hdc =
    HostedCommitments(randomKey.publicKey, randomKey.publicKey, channelId, localCommitmentSpec,
      originChannels = Map.empty, lcss1, nextLocalUpdates = Nil, nextRemoteUpdates = Nil, announceChannel = true)

  test("Processing HTLCs") {
    val (_, cmdAdd1) = makeCmdAdd(5.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Left(_: HtlcValueTooSmall) = hdc.sendAdd(cmdAdd1, blockHeight = 100)
    val (_, cmdAdd2) = makeCmdAdd(50.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Left(_: ExpiryTooSmall) = hdc.sendAdd(cmdAdd2, blockHeight = 300)
    val (_, cmdAdd3) = makeCmdAdd(50000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Right((hdc1, _)) = hdc.sendAdd(cmdAdd3, blockHeight = 100)
    assert(hdc1.nextLocalSpec.toLocal === 50000.msat)
    val (_, cmdAdd4) = makeCmdAdd(40000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Right((hdc2, _)) = hdc1.sendAdd(cmdAdd4, blockHeight = 100)
    assert(hdc2.nextLocalSpec.toLocal === 10000.msat)
    val (_, cmdAdd5) = makeCmdAdd(20000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Left(InsufficientFunds(_, _, missing, _, _)) = hdc2.sendAdd(cmdAdd5, blockHeight = 100)
    assert(missing === 10.sat)
    val (_, cmdAdd6) = makeCmdAdd(90001.msat, randomKey.publicKey, currentBlockHeight = 100)
    val Left(_: HtlcValueTooHighInFlight) = hdc.sendAdd(cmdAdd6, blockHeight = 100)
    val (bob2AliceAddPreimage, cmdAdd7) = makeCmdAdd(10000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd8) = makeCmdAdd(10000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd9) = makeCmdAdd(10000.msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd10) = makeCmdAdd(10000.msat, randomKey.publicKey, currentBlockHeight = 100)

    val Right((hdc3, bob2AliceAdd)) = hdc.sendAdd(cmdAdd7, blockHeight = 100)
    val Right((hdc4, _)) = hdc3.sendAdd(cmdAdd8, blockHeight = 100)
    val Right((hdc5, _)) = hdc4.sendAdd(cmdAdd9, blockHeight = 100)
    val Left(_: TooManyAcceptedHtlcs) = hdc5.sendAdd(cmdAdd10, blockHeight = 100)
    val Right(hdc6) = hdc5.receiveAdd(updateAddHtlc1)
    val Right(hdc7) = hdc6.receiveAdd(updateAddHtlc2)
    assert(hdc7.nextLocalSpec.toRemote === (hdc.localSpec.toRemote - updateAddHtlc1.amountMsat - updateAddHtlc2.amountMsat))
    assert(hdc7.nextLocalUnsignedLCSS(blockDay = 100).remoteUpdates === 103)
    assert(hdc7.nextLocalUnsignedLCSS(blockDay = 100).localUpdates === 204)
    val data = HC_DATA_ESTABLISHED(hdc7, channelUpdate)
    assert(data.timedOutOutgoingHtlcs(244).isEmpty)
    assert(data.timedOutOutgoingHtlcs(245).size === 3)

    val aliceSignedLCSS = hdc7.nextLocalUnsignedLCSS(blockDay = 200).withLocalSigOfRemote(alicePrivKey)
    val bobSignedLCSS = hdc7.nextLocalUnsignedLCSS(blockDay = 200).reverse.withLocalSigOfRemote(bobPrivKey)
    val aliceStateUpdatedHdc = hdc7.copy(lastCrossSignedState = aliceSignedLCSS.copy(remoteSigOfLocal = bobSignedLCSS.localSigOfRemote), localSpec = hdc7.nextLocalSpec, nextLocalUpdates = Nil, nextRemoteUpdates = Nil)
    assert(aliceStateUpdatedHdc.lastCrossSignedState.verifyRemoteSig(bobPrivKey.publicKey)) // Alice now has an updated LCSS signed by Bob
    assert(aliceStateUpdatedHdc.localSpec.htlcs.size === 5) // And 5 HTLCs in-flight

    val Right((aliceStateUpdatedHdc1, fulfill)) = aliceStateUpdatedHdc.sendFulfill(CMD_FULFILL_HTLC(updateAddHtlc1.id, preimage1))
    assert(aliceStateUpdatedHdc1.nextLocalSpec.toLocal === aliceStateUpdatedHdc1.localSpec.toLocal + updateAddHtlc1.amountMsat)
    assert(aliceStateUpdatedHdc1.nextLocalSpec.htlcs.size === 4)
    assert(aliceStateUpdatedHdc1.nextLocalUpdates === List(fulfill))
    assert(aliceStateUpdatedHdc1.nextLocalUnsignedLCSS(blockDay = 201).withLocalSigOfRemote(alicePrivKey).stateUpdate.localUpdates === 205) // Fail/Fulfill also increase an update counter

    val bobFulfill = UpdateFulfillHtlc(channelId, bob2AliceAdd.id, bob2AliceAddPreimage)
    val Right((aliceStateUpdatedHdc2, _, _)) = aliceStateUpdatedHdc1.receiveFulfill(bobFulfill)
    assert(aliceStateUpdatedHdc2.nextLocalSpec.htlcs.size === 3)
    assert(aliceStateUpdatedHdc2.nextLocalUpdates === List(fulfill))
    assert(aliceStateUpdatedHdc2.nextRemoteUpdates === List(bobFulfill))
    assert(aliceStateUpdatedHdc2.nextLocalUnsignedLCSS(blockDay = 201).withLocalSigOfRemote(alicePrivKey).stateUpdate.remoteUpdates === 104) // Fail/Fulfill also increase an update counter
  }

  test("Resize message sig check") {
    val clientKey = randomKey
    val msg = ResizeChannel(newCapacity = 100000L.sat)
    assert(msg.sign(clientKey).verifyClientSig(clientKey.publicKey))
  }
}
