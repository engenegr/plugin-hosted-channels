package fr.acinq.hc.app.dbo

import slick.jdbc.PostgresProfile.api._
import scodec.bits.{BitVector, ByteVector}
import fr.acinq.hc.app.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTSCodec
import fr.acinq.hc.app.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.hc.app.dbo.Blocking.ByteArray
import fr.acinq.bitcoin.ByteVector32
import slick.jdbc.PostgresProfile


class HostedChannelsDb(db: PostgresProfile.backend.Database) {
  private def encode(state: HOSTED_DATA_COMMITMENTS) = HOSTED_DATA_COMMITMENTSCodec.encode(state).require.toByteArray
  private def decode(data: ByteArray) = HOSTED_DATA_COMMITMENTSCodec.decode(BitVector view data).require.value

  def addNewChannel(state: HOSTED_DATA_COMMITMENTS): Boolean = {
    val tuple = (state.channelId.toArray, state.channelUpdate.shortChannelId.toLong, 0, false, None, None, state.lastCrossSignedState.blockDay, System.currentTimeMillis, encode(state), Array.emptyByteArray)
    Blocking.txWrite(Channels.insertCompiled += tuple, db) > 0 // Will throw if either `channelId` or `shortChannelId` is already present in database
  }

  def updateChannel(state: HOSTED_DATA_COMMITMENTS): Boolean = {
    val tuple = (state.currentAndNextInFlightHtlcs.size, state.announceChannel, state.refundCompleteInfo, state.refundPendingInfo.map(_.startedAt), state.lastCrossSignedState.blockDay, encode(state))
    Blocking.txWrite(Channels.findByChannelIdUpdatableCompiled(state.channelId.toArray).update(tuple), db) > 0 // Method will return false if no record exists and hence could not be updated
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
