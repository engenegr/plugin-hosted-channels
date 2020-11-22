package fr.acinq.hc.app.dbo

import slick.jdbc.PostgresProfile.api._
import fr.acinq.hc.app.wire.HostedChannelCodecs._
import scodec.bits.{BitVector, ByteVector}

import fr.acinq.hc.app.channel.HC_DATA_ESTABLISHED
import fr.acinq.hc.app.dbo.Blocking.ByteArray
import fr.acinq.bitcoin.Crypto.PublicKey
import slick.jdbc.PostgresProfile


class HostedChannelsDb(db: PostgresProfile.backend.Database) {
  private def decode(data: ByteArray) = HC_DATA_ESTABLISHED_Codec.decode(BitVector view data).require.value

  def addNewChannel(data: HC_DATA_ESTABLISHED): Boolean =
    Blocking.txWrite(Channels.insertCompiled += (data.commitments.remoteNodeId.value.toArray, data.channelUpdate.shortChannelId.toLong,
      0, data.commitments.isHost, None, None, data.commitments.lastCrossSignedState.blockDay, System.currentTimeMillis,
      HC_DATA_ESTABLISHED_Codec.encode(data).require.toByteArray, Array.emptyByteArray), db) > 0

  def updateOrAddNewChannel(data: HC_DATA_ESTABLISHED): Unit = {
    val encoded = HC_DATA_ESTABLISHED_Codec.encode(data).require.toByteArray
    val remoteNodeId = data.commitments.remoteNodeId.value.toArray
    val startedAtLong = data.refundPendingInfo.map(_.startedAt)
    val inFlightHtlcs = data.pendingHtlcs.size

    val updateTuple = (inFlightHtlcs, data.commitments.isHost, data.refundCompleteInfo, startedAtLong, data.commitments.lastCrossSignedState.blockDay, encoded)
    val updateHasFailed: Boolean = Blocking.txWrite(Channels.findByRemoteNodeIdUpdatableCompiled(remoteNodeId).update(updateTuple), db) == 0

    if (updateHasFailed) {
      Blocking.txWrite(Channels.insertCompiled += (remoteNodeId, data.channelUpdate.shortChannelId.toLong, inFlightHtlcs,
        data.commitments.isHost, data.refundCompleteInfo, startedAtLong, data.commitments.lastCrossSignedState.blockDay,
        System.currentTimeMillis, encoded, Array.emptyByteArray), db)
    }
  }

  def updateSecretById(remoteNodeId: PublicKey, secret: ByteVector): Boolean =
    Blocking.txWrite(Channels.findSecretUpdatableByRemoteNodeIdCompiled(remoteNodeId.value.toArray).update(secret.toArray), db) > 0

  def getChannelByRemoteNodeId(remoteNodeId: PublicKey): Option[HC_DATA_ESTABLISHED] = for {
    (_, _, _, _, _, data) <- Blocking.txRead(Channels.findByRemoteNodeIdUpdatableCompiled(remoteNodeId.value.toArray).result.headOption, db)
  } yield decode(data)

  def getChannelBySecret(secret: ByteVector): Option[HC_DATA_ESTABLISHED] =
    Blocking.txRead(Channels.findBySecretCompiled(secret.toArray).result.headOption, db).map(decode)

  def listHotChannels: Seq[HC_DATA_ESTABLISHED] =
    Blocking.txRead(Channels.listHotChannelsCompiled.result, db).map(decode)

  def listClientChannels: Seq[HC_DATA_ESTABLISHED] =
    Blocking.txRead(Channels.listClientChannelsCompiled.result, db).map(decode)
}
