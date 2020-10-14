package fr.acinq.hc.app.wire

import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.hc.app.wire.Codecs.{stateOverrideCodec, lastCrossSignedStateCodec, refundPendingCodec}
import scodec.codecs.{bool, either, listOfN, optional, uint16, uint8, utf8, variableSizeBytes}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, setCodec, uint64overflow, publicKey}
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, errorCodec}
import fr.acinq.hc.app.channel.{HOSTED_DATA_COMMITMENTS, HostedState}
import scodec.Codec


object HostedChannelCodecs {
  val HOSTED_DATA_COMMITMENTSCodec: Codec[HOSTED_DATA_COMMITMENTS] = {
    (publicKey withContext "localNodeId") ::
      (publicKey withContext "remoteNodeId") ::
      (channelVersionCodec withContext "channelVersion") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState") ::
      (listOfN(uint8, either(bool, updateMessageCodec, updateMessageCodec)) withContext "futureUpdates") ::
      (commitmentSpecCodec withContext "localSpec") ::
      (originsMapCodec withContext "originChannels") ::
      (bool withContext "isHost") ::
      (variableSizeBytes(uint16, channelUpdateCodec) withContext "channelUpdate") ::
      (optional(bool, errorCodec) withContext "localError") ::
      (optional(bool, errorCodec) withContext "remoteError") ::
      (setCodec(uint64overflow) withContext "failedToPeerHtlcLeftoverIds") ::
      (setCodec(uint64overflow) withContext "fulfilledByPeerHtlcLeftoverIds") ::
      (optional(bool, stateOverrideCodec) withContext "overrideProposal") ::
      (optional(bool, refundPendingCodec) withContext "refundPendingInfo") ::
      (optional(bool, variableSizeBytes(uint16, utf8)) withContext "refundCompleteInfo") ::
      (bool withContext "announceChannel")
  }.as[HOSTED_DATA_COMMITMENTS]

  val hostedStateCodec: Codec[HostedState] = {
    (bytes32 withContext "channelId") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextLocalUpdates") ::
      (listOfN(uint16, updateMessageCodec) withContext "nextRemoteUpdates") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
