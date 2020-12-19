package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering, Protocol, Satoshi}
import fr.acinq.eclair.wire.{Color, LightningMessageCodecs, UpdateAddHtlc}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import scodec.bits.ByteVector
import java.nio.ByteOrder


sealed trait HostedChannelMessage

case class InvokeHostedChannel(chainHash: ByteVector32,
                               refundScriptPubKey: ByteVector,
                               secret: ByteVector = ByteVector.empty) extends HostedChannelMessage {
  val finalSecret: ByteVector = secret.take(128)
}

case class InitHostedChannel(maxHtlcValueInFlightMsat: UInt64,
                             htlcMinimumMsat: MilliSatoshi,
                             maxAcceptedHtlcs: Int,
                             channelCapacityMsat: MilliSatoshi,
                             liabilityDeadlineBlockdays: Int,
                             minimalOnchainRefundAmountSatoshis: Satoshi,
                             initialClientBalanceMsat: MilliSatoshi) extends HostedChannelMessage

case class HostedChannelBranding(rgbColor: Color,
                                 pngIcon: ByteVector,
                                 contactInfo: String) extends HostedChannelMessage

case class LastCrossSignedState(refundScriptPubKey: ByteVector,
                                initHostedChannel: InitHostedChannel,
                                blockDay: Long,
                                localBalanceMsat: MilliSatoshi,
                                remoteBalanceMsat: MilliSatoshi,
                                localUpdates: Long,
                                remoteUpdates: Long,
                                incomingHtlcs: List[UpdateAddHtlc],
                                outgoingHtlcs: List[UpdateAddHtlc],
                                remoteSigOfLocal: ByteVector64,
                                localSigOfRemote: ByteVector64) extends HostedChannelMessage {

  lazy val reverse: LastCrossSignedState =
    copy(localUpdates = remoteUpdates, remoteUpdates = localUpdates,
      localBalanceMsat = remoteBalanceMsat, remoteBalanceMsat = localBalanceMsat,
      remoteSigOfLocal = localSigOfRemote, localSigOfRemote = remoteSigOfLocal,
      incomingHtlcs = outgoingHtlcs, outgoingHtlcs = incomingHtlcs)

  lazy val hostedSigHash: ByteVector32 = {
    val inPayments = incomingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)
    val outPayments = outgoingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)

    val preimage =
      refundScriptPubKey ++
        Protocol.writeUInt16(initHostedChannel.liabilityDeadlineBlockdays, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.minimalOnchainRefundAmountSatoshis.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.channelCapacityMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.initialClientBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(blockDay, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(localBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(remoteBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(localUpdates, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(remoteUpdates, ByteOrder.LITTLE_ENDIAN) ++
        inPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc } ++
        outPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc }

    Crypto.sha256(preimage)
  }

  def verifyRemoteSig(pubKey: PublicKey): Boolean = Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: PrivateKey): LastCrossSignedState = copy(localSigOfRemote = Crypto.sign(reverse.hostedSigHash, priv))

  def stateUpdate: StateUpdate = StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote)
}

case class StateUpdate(blockDay: Long, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage

case class StateOverride(blockDay: Long, localBalanceMsat: MilliSatoshi, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage

case class RefundPending(startedAt: Long) extends HostedChannelMessage

case class AnnouncementSignature(nodeSignature: ByteVector64, wantsReply: Boolean) extends HostedChannelMessage

case class ResizeChannel(newCapacity: MilliSatoshi, clientSig: ByteVector64 = ByteVector64.Zeroes) extends HostedChannelMessage {
  def sign(priv: PrivateKey): ResizeChannel = ResizeChannel(clientSig = Crypto.sign(Crypto.sha256(sigMaterial), priv), newCapacity = newCapacity)
  def verifyClientSig(pubKey: PublicKey): Boolean = Crypto.verifySignature(data = Crypto.sha256(sigMaterial), clientSig, pubKey)
  def isRemoteResized(remote: LastCrossSignedState): Boolean = remote.initHostedChannel.channelCapacityMsat == newCapacity
  lazy val sigMaterial: ByteVector = Protocol.writeUInt64(newCapacity.toLong, ByteOrder.LITTLE_ENDIAN)
  lazy val newCapacityU64: UInt64 = UInt64(newCapacity.toLong)
}

// PHC

case class QueryPublicHostedChannels(chainHash: ByteVector32) extends HostedChannelMessage

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32) extends HostedChannelMessage
