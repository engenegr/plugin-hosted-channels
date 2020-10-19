package fr.acinq.hc.app.wire

import fr.acinq.hc.app._
import fr.acinq.hc.app.HC._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.codecs.{bool, listOfN, uint16, uint32}
import scodec.{Attempt, Codec, DecodeResult, Err}


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

  val queryPublicHostedChannelsCodec: Codec[QueryPublicHostedChannels] =
    (bytes32 withContext "chainHash").as[QueryPublicHostedChannels]

  val replyPublicHostedChannelsEndCodec: Codec[ReplyPublicHostedChannelsEnd] =
    (bytes32 withContext "chainHash").as[ReplyPublicHostedChannelsEnd]


  def decode(wrap: UnknownMessage): Attempt[HostedChannelMessage] = wrap.tag match {
    case HC_INVOKE_HOSTED_CHANNEL_TAG => invokeHostedChannelCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_INIT_HOSTED_CHANNEL_TAG => initHostedChannelCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_LAST_CROSS_SIGNED_STATE_TAG => lastCrossSignedStateCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_STATE_UPDATE_TAG => stateUpdateCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_STATE_OVERRIDE_TAG => stateOverrideCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_HOSTED_CHANNEL_BRANDING_TAG => hostedChannelBrandingCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_REFUND_PENDING_TAG => refundPendingCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG => queryPublicHostedChannelsCodec.decode(wrap.data.toBitVector).map(_.value)
    case HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG => replyPublicHostedChannelsEndCodec.decode(wrap.data.toBitVector).map(_.value)
  }

  def toUnknownMessage(message: HostedChannelMessage): UnknownMessage = message match {
    case msg: InvokeHostedChannel => UnknownMessage(HC_INVOKE_HOSTED_CHANNEL_TAG, invokeHostedChannelCodec.encode(msg).require.toByteVector)
    case msg: InitHostedChannel => UnknownMessage(HC_INIT_HOSTED_CHANNEL_TAG, initHostedChannelCodec.encode(msg).require.toByteVector)
    case msg: LastCrossSignedState => UnknownMessage(HC_LAST_CROSS_SIGNED_STATE_TAG, lastCrossSignedStateCodec.encode(msg).require.toByteVector)
    case msg: StateUpdate => UnknownMessage(HC_STATE_UPDATE_TAG, stateUpdateCodec.encode(msg).require.toByteVector)
    case msg: StateOverride => UnknownMessage(HC_STATE_OVERRIDE_TAG, stateOverrideCodec.encode(msg).require.toByteVector)
    case msg: HostedChannelBranding => UnknownMessage(HC_HOSTED_CHANNEL_BRANDING_TAG, hostedChannelBrandingCodec.encode(msg).require.toByteVector)
    case msg: RefundPending => UnknownMessage(HC_REFUND_PENDING_TAG, refundPendingCodec.encode(msg).require.toByteVector)
    case msg: QueryPublicHostedChannels => UnknownMessage(HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG, queryPublicHostedChannelsCodec.encode(msg).require.toByteVector)
    case msg: ReplyPublicHostedChannelsEnd => UnknownMessage(HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG, replyPublicHostedChannelsEndCodec.encode(msg).require.toByteVector)
  }


  def decodeAnnounceMessage(wrap: UnknownMessage): Attempt[AnnouncementMessage] = wrap.tag match {
    case PHC_ANNOUNCE_GOSSIP_TAG => LightningMessageCodecs.channelAnnouncementCodec.decode(wrap.data.toBitVector).map(_.value)
    case PHC_ANNOUNCE_SYNC_TAG => LightningMessageCodecs.channelAnnouncementCodec.decode(wrap.data.toBitVector).map(_.value)
    case PHC_UPDATE_GOSSIP_TAG => LightningMessageCodecs.channelUpdateCodec.decode(wrap.data.toBitVector).map(_.value)
    case PHC_UPDATE_SYNC_TAG => LightningMessageCodecs.channelUpdateCodec.decode(wrap.data.toBitVector).map(_.value)
    case tag => Attempt failure Err(s"PLGN PHC, unsupported gossip tag=$tag")
  }

  def toUnknownAnnounceMessage(message: AnnouncementMessage, isGossip: Boolean): UnknownMessage = message match {
    case msg: ChannelAnnouncement if isGossip => UnknownMessage(PHC_ANNOUNCE_GOSSIP_TAG, LightningMessageCodecs.channelAnnouncementCodec.encode(msg).require.toByteVector)
    case msg: ChannelAnnouncement => UnknownMessage(PHC_ANNOUNCE_SYNC_TAG, LightningMessageCodecs.channelAnnouncementCodec.encode(msg).require.toByteVector)
    case msg: ChannelUpdate if isGossip => UnknownMessage(PHC_UPDATE_GOSSIP_TAG, LightningMessageCodecs.channelUpdateCodec.encode(msg).require.toByteVector)
    case msg: ChannelUpdate => UnknownMessage(PHC_UPDATE_SYNC_TAG, LightningMessageCodecs.channelUpdateCodec.encode(msg).require.toByteVector)
    case msg => throw new RuntimeException(s"PLGN PHC, unacceptable routing message=${msg.getClass.toString}")
  }
}
