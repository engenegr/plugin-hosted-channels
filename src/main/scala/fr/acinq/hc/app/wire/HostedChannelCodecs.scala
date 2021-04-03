package fr.acinq.hc.app.wire

import fr.acinq.hc.app.wire.Codecs._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, channelAnnouncementCodec, errorCodec}
import fr.acinq.hc.app.channel.{ErrorExt, HC_DATA_ESTABLISHED, HostedCommitments, HostedState}
import scodec.codecs.{listOfN, optional, uint16, uint8, utf8, variableSizeBytes}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, publicKey}
import scodec.Codec


object HostedChannelCodecs {
  val hostedCommitmentsCodec: Codec[HostedCommitments] = {
    (publicKey withContext "localNodeId") ::
      (publicKey withContext "remoteNodeId") ::
      (bytes32 withContext "channelId") ::
      (commitmentSpecCodec withContext "localSpec") ::
      (originsMapCodec withContext "originChannels") ::
      (lengthDelimited(lastCrossSignedStateCodec) withContext "lastCrossSignedState") ::
      (listOfN(uint8, updateMessageWithHasChannelIdCodec) withContext "nextLocalUpdates") ::
      (listOfN(uint8, updateMessageWithHasChannelIdCodec) withContext "nextRemoteUpdates") ::
      (bool8 withContext "announceChannel")
  }.as[HostedCommitments]

  val errorExtCodec: Codec[ErrorExt] = {
    (lengthDelimited(errorCodec) withContext "localError") ::
      (variableSizeBytes(uint16, utf8) withContext "stamp") ::
      (variableSizeBytes(uint16, utf8) withContext "description")
  }.as[ErrorExt]

  val HC_DATA_ESTABLISHED_Codec: Codec[HC_DATA_ESTABLISHED] = {
    (hostedCommitmentsCodec withContext "commitments") ::
      (lengthDelimited(channelUpdateCodec) withContext "channelUpdate") ::
      (listOfN(uint8, errorExtCodec) withContext "localErrors") ::
      (optional(bool8, errorExtCodec) withContext "remoteError") ::
      (optional(bool8, lengthDelimited(resizeChannelCodec)) withContext "resizeProposal") ::
      (optional(bool8, lengthDelimited(stateOverrideCodec)) withContext "overrideProposal") ::
      (optional(bool8, lengthDelimited(refundPendingCodec)) withContext "refundPendingInfo") ::
      (optional(bool8, variableSizeBytes(uint16, utf8)) withContext "refundCompleteInfo") ::
      (optional(bool8, lengthDelimited(channelAnnouncementCodec)) withContext "channelAnnouncement")
  }.as[HC_DATA_ESTABLISHED]

  val hostedStateCodec: Codec[HostedState] = {
    (publicKey withContext "nodeId1") ::
      (publicKey withContext "nodeId2") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
