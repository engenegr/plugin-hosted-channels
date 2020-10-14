package fr.acinq.hc.app.wire

import fr.acinq.hc.app._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.codecs.{bool, listOfN, uint16, uint32}
import fr.acinq.eclair.wire.LightningMessageCodecs
import scodec.Codec


object Codecs {
  val invokeHostedChannelCodec: Codec[InvokeHostedChannel] = {
    (bytes32 withContext "chainHash") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (varsizebinarydata withContext "secret")
  }.as[InvokeHostedChannel]

  val initHostedChannelCodec: Codec[InitHostedChannel] = {
    (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (millisatoshi withContext "htlcMinimumMsat") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (millisatoshi withContext "channelCapacityMsat") ::
      (uint16 withContext "liabilityDeadlineBlockdays") ::
      (satoshi withContext "minimalOnchainRefundAmountSatoshis") ::
      (millisatoshi withContext "initialClientBalanceMsat")
  }.as[InitHostedChannel]

  val hostedChannelBrandingCodec: Codec[HostedChannelBranding] = {
    (rgb withContext "rgbColor") ::
      (varsizebinarydata withContext "pngIcon")
  }.as[HostedChannelBranding]

  val lastCrossSignedStateCodec: Codec[LastCrossSignedState] = {
    (varsizebinarydata withContext "refundScriptPubKey") ::
      (initHostedChannelCodec withContext "initHostedChannel") ::
      (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (millisatoshi withContext "remoteBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (listOfN(uint16, LightningMessageCodecs.updateAddHtlcCodec) withContext "incomingHtlcs") ::
      (listOfN(uint16, LightningMessageCodecs.updateAddHtlcCodec) withContext "outgoingHtlcs") ::
      (bytes64 withContext "remoteSigOfLocal") ::
      (bytes64 withContext "localSigOfRemote")
  }.as[LastCrossSignedState]

  val stateUpdateCodec: Codec[StateUpdate] = {
    (uint32 withContext "blockDay") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS") ::
      (bool withContext "isTerminal")
  }.as[StateUpdate]

  val stateOverrideCodec: Codec[StateOverride] = {
    (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateOverride]

  val refundPendingCodec: Codec[RefundPending] =
    (uint32 withContext "startedAt").as[RefundPending]

  val queryPublicHostedChannelsCodec: Codec[QueryPublicHostedChannels] = (bytes32 withContext "chainHash").as[QueryPublicHostedChannels]

  val replyPublicHostedChannelsEndCodec: Codec[ReplyPublicHostedChannelsEnd] = (bytes32 withContext "chainHash").as[ReplyPublicHostedChannelsEnd]
}
