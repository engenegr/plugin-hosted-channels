package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app._
import fr.acinq.eclair.channel._

import scala.util.{Failure, Success, Try}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.hc.app.wire.Codecs.{LocalOrRemoteUpdateWithChannelId, UpdateWithChannelId}
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector
import fr.acinq.eclair.wire

// Commands

sealed trait HasRemoteNodeIdHostedCommand {
  def remoteNodeId: PublicKey
}

case class HC_CMD_LOCAL_INVOKE(remoteNodeId: PublicKey, refundScriptPubKey: ByteVector, secret: ByteVector) extends HasRemoteNodeIdHostedCommand

case class HC_CMD_EXTERNAL_FULFILL(remoteNodeId: PublicKey, htlcId: Long, paymentPreimage: ByteVector32) extends HasRemoteNodeIdHostedCommand

case class HC_CMD_OVERRIDE_PROPOSE(remoteNodeId: PublicKey, newLocalBalance: MilliSatoshi) extends HasRemoteNodeIdHostedCommand
case class HC_CMD_OVERRIDE_ACCEPT(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand

case class HC_CMD_INIT_PENDING_REFUND(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand
case class HC_CMD_FINALIZE_REFUND(remoteNodeId: PublicKey, info: String, force: Boolean = false) extends HasRemoteNodeIdHostedCommand

case class HC_CMD_PUBLIC(remoteNodeId: PublicKey, force: Boolean = false) extends HasRemoteNodeIdHostedCommand
case class HC_CMD_PRIVATE(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand

case class HC_CMD_GET_INFO(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand

case class CMDResSuccess(cmd: HasRemoteNodeIdHostedCommand)

case class CMDResponseInfo(state: State, data: HC_DATA_ESTABLISHED, nextLocalSpec: CommitmentSpec)

// Data

sealed trait HostedData

case object HC_NOTHING extends HostedData

case class HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(invoke: InvokeHostedChannel) extends HostedData

case class HC_DATA_CLIENT_WAIT_HOST_INIT(refundScriptPubKey: ByteVector) extends HostedData

case class HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commitments: HostedCommitments) extends HostedData

case class HC_DATA_ESTABLISHED(commitments: HostedCommitments,
                               channelUpdate: wire.ChannelUpdate,
                               localError: Option[ErrorExt] = None,
                               remoteError: Option[ErrorExt] = None,
                               overrideProposal: Option[StateOverride] = None, // CLOSED channel override can be initiated by Host
                               refundPendingInfo: Option[RefundPending] = None, // Will be present in case if funds should be refunded
                               refundCompleteInfo: Option[String] = None, // Will be present after channel has been manually updated as a refunded one
                               channelAnnouncement: Option[wire.ChannelAnnouncement] = None) extends HostedData {

  lazy val commitmentsOpt: Option[HostedCommitments] = Some(commitments)

  lazy val errorExt: Option[ErrorExt] = localError orElse remoteError

  lazy val pendingHtlcs: Set[DirectedHtlc] = if (errorExt.isEmpty) {
    // In operational state a peer may send FAIL or we may send (and sign) ADD without subsequent state update
    // so we must always look at both localSpec and nextLocalSpec to always see an entire pending HTLC set
    commitments.localSpec.htlcs ++ commitments.nextLocalSpec.htlcs
  } else {
    // Clearing of HTLCs in localSpec is impossible when error is present
    // OTOH fails and fulfills from remote peer are not accepted in such state either
    // pending HTLCs can be cleared by our fake FAIL on timeout or by FULFILL from peer
    commitments.nextLocalSpec.htlcs
  }

  def timedOutOutgoingHtlcs(blockHeight: Long): Set[wire.UpdateAddHtlc] =
    pendingHtlcs.collect(DirectedHtlc.outgoing).filter(blockHeight > _.cltvExpiry.toLong)
}

case class HostedCommitments(isHost: Boolean,
                             localNodeId: PublicKey,
                             remoteNodeId: PublicKey,
                             channelId: ByteVector32,
                             localSpec: CommitmentSpec,
                             originChannels: Map[Long, Origin],
                             lastCrossSignedState: LastCrossSignedState,
                             futureUpdates: List[LocalOrRemoteUpdateWithChannelId],
                             announceChannel: Boolean) extends AbstractCommitments {

  val (nextLocalUpdates, nextRemoteUpdates, nextTotalLocal, nextTotalRemote) =
    futureUpdates.foldLeft((List.empty[UpdateWithChannelId], List.empty[UpdateWithChannelId], lastCrossSignedState.localUpdates, lastCrossSignedState.remoteUpdates)) {
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Left(msg)) => (localMessages :+ msg, remoteMessages, totalLocalNumber + 1, totalRemoteNumber)
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Right(msg)) => (localMessages, remoteMessages :+ msg, totalLocalNumber, totalRemoteNumber + 1)
    }

  val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  val capacity: Satoshi = lastCrossSignedState.initHostedChannel.channelCapacityMsat.truncateToSatoshi

  def addProposal(update: LocalOrRemoteUpdateWithChannelId): HostedCommitments = copy(futureUpdates = futureUpdates :+ update)

  // Find a cross-signed (in localSpec) and still not resolved (also in nextLocalSpec)
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[wire.UpdateAddHtlc] =
    for {
      localSigned <- localSpec.findOutgoingHtlcById(htlcId)
      remoteSigned <- nextLocalSpec.findOutgoingHtlcById(htlcId)
    } yield {
      require(localSigned.add == remoteSigned.add)
      localSigned.add
    }

  def getIncomingHtlcCrossSigned(htlcId: Long): Option[wire.UpdateAddHtlc] =
    for {
      localSigned <- localSpec.findIncomingHtlcById(htlcId)
      remoteSigned <- nextLocalSpec.findIncomingHtlcById(htlcId)
    } yield {
      require(localSigned.add == remoteSigned.add)
      localSigned.add
    }

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = LastCrossSignedState(lastCrossSignedState.refundScriptPubKey,
    lastCrossSignedState.initHostedChannel, blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote,
    nextLocalSpec.htlcs.collect(DirectedHtlc.incoming).toList, nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).toList,
    localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)

  // Rebuild all messaging and state history starting from local LCSS,
  // then try to find a future state with same update numbers as remote LCSS
  def findState(remoteLCSS: LastCrossSignedState): Seq[HostedCommitments] =
    for {
      previousIndex <- futureUpdates.indices drop 1
      previousHC = copy(futureUpdates = futureUpdates take previousIndex)
      if previousHC.nextLocalUnsignedLCSS(remoteLCSS.blockDay).isEven(remoteLCSS)
    } yield previousHC

  def sendAdd(cmd: CMD_ADD_HTLC, blockHeight: Long): Either[ChannelException, (HostedCommitments, wire.UpdateAddHtlc)] = {
    val minExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    if (cmd.amount < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      return Left(HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = cmd.amount))
    }

    val add = wire.UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val commits1 = addProposal(Left(add)).copy(originChannels = originChannels + (add.id -> cmd.origin))
    val outgoingHtlcs = commits1.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing)

    if (commits1.nextLocalSpec.toLocal < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -commits1.nextLocalSpec.toLocal.truncateToSatoshi, reserve = 0.sat, fees = 0.sat))
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (outgoingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs))
    }

    Right(commits1, add)
  }

  def receiveAdd(add: wire.UpdateAddHtlc): Try[HostedCommitments] = Try {
    if (add.id != nextTotalRemote + 1) {
      throw UnexpectedHtlcId(channelId, expected = nextTotalRemote + 1, actual = add.id)
    }

    if (add.amountMsat < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) {
      throw HtlcValueTooSmall(channelId, minimum = lastCrossSignedState.initHostedChannel.htlcMinimumMsat, actual = add.amountMsat)
    }

    val commits1 = addProposal(Right(add))
    val incomingHtlcs = commits1.nextLocalSpec.htlcs.collect(DirectedHtlc.incoming)

    if (commits1.nextLocalSpec.toRemote < 0.msat) {
      throw InsufficientFunds(channelId, amount = add.amountMsat, missing = -commits1.nextLocalSpec.toRemote.truncateToSatoshi, reserve = 0.sat, fees = 0.sat)
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      throw HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
    }

    if (incomingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs)
    }

    commits1
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Try[(HostedCommitments, wire.UpdateFulfillHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(add) if add.paymentHash == Crypto.sha256(cmd.r) =>
        val fulfill = wire.UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        Success(addProposal(Left(fulfill)), fulfill)
      case Some(_) => Failure(InvalidHtlcPreimage(channelId, cmd.id))
      case None => Failure(UnknownHtlcId(channelId, cmd.id))
    }

  def receiveFulfill(fulfill: wire.UpdateFulfillHtlc): Try[(HostedCommitments, Origin, wire.UpdateAddHtlc)] =
    // Technically peer may send a preimage at any moment, even if new LCSS has not been reached yet so do our best and always resolve on getting it
    // this is why for fulfills we look at `nextLocalSpec` only which may contain our not-yet-cross-signed Add which they may fulfill right away
    nextLocalSpec.findOutgoingHtlcById(fulfill.id) match {
      case Some(htlc) if htlc.add.paymentHash == Crypto.sha256(fulfill.paymentPreimage) => Success((addProposal(Right(fulfill)), originChannels(fulfill.id), htlc.add))
      case Some(_) => Failure(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Failure(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Try[(HostedCommitments, wire.UpdateFailHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(add) => OutgoingPacket.buildHtlcFailure(nodeSecret, cmd, add).map(updateFail => (addProposal(Left(updateFail)), updateFail))
      case None => Failure(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Try[(HostedCommitments, wire.UpdateFailMalformedHtlc)] =
    if ((cmd.failureCode & wire.FailureMessageCodecs.BADONION) == 0) Failure(InvalidFailureCode(channelId))
    else if (getIncomingHtlcCrossSigned(cmd.id).isEmpty) Failure(UnknownHtlcId(channelId, cmd.id))
    else {
      val fail = wire.UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
      Success(addProposal(Left(fail)), fail)
    }

  def receiveFail(fail: wire.UpdateFailHtlc): Try[HostedCommitments] =
    // Unlike Fulfill, for Fail/FailMalformed we make sure they fail our cross-signed outgoing payment
    if (getOutgoingHtlcCrossSigned(fail.id).isEmpty) Failure(UnknownHtlcId(channelId, fail.id))
    else Success(addProposal(Right(fail)))

  def receiveFailMalformed(fail: wire.UpdateFailMalformedHtlc): Try[HostedCommitments] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & wire.FailureMessageCodecs.BADONION) == 0) Failure(InvalidFailureCode(channelId))
    else if (getOutgoingHtlcCrossSigned(fail.id).isEmpty) Failure(UnknownHtlcId(channelId, fail.id))
    else Success(addProposal(Right(fail)))
  }
}

case class HostedState(channelId: ByteVector32,
                       nextLocalUpdates: List[wire.UpdateMessage],
                       nextRemoteUpdates: List[wire.UpdateMessage],
                       lastCrossSignedState: LastCrossSignedState)

case class RemoteHostedStateResult(state: HostedState, isLocalSigValid: Boolean)

// Channel errors

case class InvalidBlockDay(override val channelId: ByteVector32, localBlockDay: Long, remoteBlockDay: Long) extends ChannelException(channelId, s"invalid block day local=$localBlockDay remote=$remoteBlockDay")

case class InvalidRemoteStateSignature(override val channelId: ByteVector32) extends ChannelException(channelId, s"invalid remote state signature")
