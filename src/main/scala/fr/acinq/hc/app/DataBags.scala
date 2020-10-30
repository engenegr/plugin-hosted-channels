package fr.acinq.hc.app

import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering, Protocol, Satoshi}
import fr.acinq.eclair.wire.{Color, LightningMessageCodecs, UpdateAddHtlc}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{MilliSatoshi, UInt64}
import scodec.bits.ByteVector
import java.nio.ByteOrder


trait HostedChannelMessage

case class InvokeHostedChannel(chainHash: ByteVector32,
                               refundScriptPubKey: ByteVector,
                               secret: ByteVector) extends HostedChannelMessage {
  val finalSecret: ByteVector = secret.take(64)
}

case class InitHostedChannel(maxHtlcValueInFlightMsat: UInt64,
                             htlcMinimumMsat: MilliSatoshi,
                             maxAcceptedHtlcs: Int,
                             channelCapacityMsat: MilliSatoshi,
                             liabilityDeadlineBlockdays: Int,
                             minimalOnchainRefundAmountSatoshis: Satoshi,
                             initialClientBalanceMsat: MilliSatoshi) extends HostedChannelMessage

case class HostedChannelBranding(rgbColor: Color, pngIcon: ByteVector) extends HostedChannelMessage

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

  def isAhead(remoteLCSS: LastCrossSignedState): Boolean = remoteUpdates > remoteLCSS.localUpdates || localUpdates > remoteLCSS.remoteUpdates

  def isEven(remoteLCSS: LastCrossSignedState): Boolean = remoteUpdates == remoteLCSS.localUpdates && localUpdates == remoteLCSS.remoteUpdates

  def stateUpdate(isTerminal: Boolean): StateUpdate = StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote, isTerminal)
}

case class StateUpdate(blockDay: Long, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64, isTerminal: Boolean) extends HostedChannelMessage

case class StateOverride(blockDay: Long, localBalanceMsat: MilliSatoshi, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage

case class RefundPending(startedAt: Long) extends HostedChannelMessage

// PHC

trait HostedChannelRoutingMessage

case class QueryPublicHostedChannels(chainHash: ByteVector32) extends HostedChannelMessage with HostedChannelRoutingMessage

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32) extends HostedChannelMessage with HostedChannelRoutingMessage
