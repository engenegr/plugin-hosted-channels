package fr.acinq.hc.app.wire

import fr.acinq.hc.app.wire.Codecs._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.hc.app.channel.{ErrorExt, HC_DATA_ESTABLISHED, HostedCommitments, HostedState}
import scodec.codecs.{bool, either, listOfN, optional, uint16, uint8, utf8, variableSizeBytes}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, publicKey, setCodec, uint64overflow}
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, errorCodec}
import scodec.Codec


object HostedChannelCodecs {
  val hostedCommitmentsCodec: Codec[HostedCommitments] = {
    (bool withContext "isHost") ::
      (publicKey withContext "localNodeId") ::
      (publicKey withContext "remoteNodeId") ::
      (bytes32 withContext "channelId") ::
      (commitmentSpecCodec withContext "localSpec") ::
      (originsMapCodec withContext "originChannels") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState") ::
      (listOfN(uint8, either(bool, updateWithChannelIdCodec, updateWithChannelIdCodec)) withContext "futureUpdates") ::
      (setCodec(uint64overflow) withContext "timedOutToPeerHtlcLeftOverIds") ::
      (setCodec(uint64overflow) withContext "fulfilledByPeerHtlcLeftOverIds") ::
      (bool withContext "announceChannel")
  }.as[HostedCommitments]

  val errorExtCodec: Codec[ErrorExt] = {
    (errorCodec withContext "localError") ::
      (variableSizeBytes(uint16, utf8) withContext "stamp") ::
      (variableSizeBytes(uint16, utf8) withContext "description")
  }.as[ErrorExt]

  val HC_DATA_ESTABLISHED_Codec: Codec[HC_DATA_ESTABLISHED] = {
    (hostedCommitmentsCodec withContext "commitments") ::
      (optional(bool, errorExtCodec) withContext "localError") ::
      (optional(bool, errorExtCodec) withContext "remoteError") ::
      (optional(bool, stateOverrideCodec) withContext "overrideProposal") ::
      (optional(bool, refundPendingCodec) withContext "refundPendingInfo") ::
      (optional(bool, variableSizeBytes(uint16, utf8)) withContext "refundCompleteInfo") ::
      (variableSizeBytes(uint16, channelUpdateCodec) withContext "localChannelUpdate")
  }.as[HC_DATA_ESTABLISHED]

  val hostedStateCodec: Codec[HostedState] = {
    (bytes32 withContext "channelId") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextLocalUpdates") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextRemoteUpdates") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
