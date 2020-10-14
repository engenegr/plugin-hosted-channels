package fr.acinq.hc.app.dbo

import slick.jdbc.PostgresProfile.api._
import scodec.bits.{BitVector, ByteVector}
import fr.acinq.hc.app.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTSCodec
import fr.acinq.hc.app.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.hc.app.dbo.Blocking.ByteArray
import fr.acinq.bitcoin.ByteVector32
import slick.jdbc.PostgresProfile


class HostedChannelsDb(db: PostgresProfile.backend.Database) {
  private def decode(data: ByteArray) = HOSTED_DATA_COMMITMENTSCodec.decode(BitVector view data).require.value

  def addNewChannel(state: HOSTED_DATA_COMMITMENTS): Boolean =
    Blocking.txWrite(Channels.insertCompiled += (state.channelId.toArray, state.channelUpdate.shortChannelId.toLong, 0, false, None, None,
      state.lastCrossSignedState.blockDay, System.currentTimeMillis, HOSTED_DATA_COMMITMENTSCodec.encode(state).require.toByteArray,
      Array.emptyByteArray), db) > 0

  def updateOrAddNewChannel(state: HOSTED_DATA_COMMITMENTS): Unit = {
    val encoded = HOSTED_DATA_COMMITMENTSCodec.encode(state).require.toByteArray
    val startedAtLong = state.refundPendingInfo.map(_.startedAt)
    val inFlightHtlcs = state.currentAndNextInFlightHtlcs.size
    val channelId = state.channelId.toArray

    val updateTuple = (inFlightHtlcs, state.announceChannel, state.refundCompleteInfo, startedAtLong, state.lastCrossSignedState.blockDay, encoded)
    val updateHasFailed: Boolean = Blocking.txWrite(Channels.findByChannelIdUpdatableCompiled(channelId).update(updateTuple), db) == 0

    if (updateHasFailed) {
      Blocking.txWrite(Channels.insertCompiled += (channelId, state.channelUpdate.shortChannelId.toLong, inFlightHtlcs,
        state.announceChannel, state.refundCompleteInfo, startedAtLong, state.lastCrossSignedState.blockDay,
        System.currentTimeMillis, encoded, Array.emptyByteArray), db)
    }
  }

  def updateSecretById(channelId: ByteVector32, secret: ByteVector): Boolean =
    Blocking.txWrite(Channels.findSecretUpdatableByIdCompiled(channelId.toArray).update(secret.toArray), db) > 0

  def getChannelById(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = for {
    (_, _, _, _, _, data) <- Blocking.txRead(Channels.findByChannelIdUpdatableCompiled(channelId.toArray).result.headOption, db)
  } yield decode(data)

  def getChannelBySecret(secret: ByteVector): Option[HOSTED_DATA_COMMITMENTS] =
    Blocking.txRead(Channels.findBySecretCompiled(secret.toArray).result.headOption, db).map(decode)

  def listHotChannels: Seq[HOSTED_DATA_COMMITMENTS] =
    Blocking.txRead(Channels.listHotChannelsCompiled.result, db).map(decode)

  def listPublicChannels: Seq[HOSTED_DATA_COMMITMENTS] =
    Blocking.txRead(Channels.listPublicChannelsCompiled.result, db).map(decode)
}
