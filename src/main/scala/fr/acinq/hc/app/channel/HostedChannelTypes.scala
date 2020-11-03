package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.hc.app._
import fr.acinq.eclair.channel._

import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, OutgoingHtlc}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import scala.util.{Failure, Success, Try}

import fr.acinq.hc.app.channel.HostedCommitments.LocalOrRemoteUpdate
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector
import fr.acinq.eclair.wire

// Commands

sealed trait HasRemoteNodeIdHostedCommand {
  def remoteNodeId: PublicKey
}

case class CMD_HOSTED_LOCAL_INVOKE(remoteNodeId: PublicKey, refundScriptPubKey: ByteVector, secret: ByteVector) extends HasRemoteNodeIdHostedCommand

case class CMD_HOSTED_EXTERNAL_FULFILL(remoteNodeId: PublicKey, htlcId: Long, paymentPreimage: ByteVector32) extends HasRemoteNodeIdHostedCommand

case class CMD_HOSTED_OVERRIDE(remoteNodeId: PublicKey, newLocalBalance: MilliSatoshi) extends HasRemoteNodeIdHostedCommand

case class CMD_INIT_PENDING_REFUND(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand
case class CMD_FINALIZE_REFUND(remoteNodeId: PublicKey, info: String) extends HasRemoteNodeIdHostedCommand

case class CMD_TURN_PUBLIC(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand
case class CMD_TURN_PRIVATE(remoteNodeId: PublicKey) extends HasRemoteNodeIdHostedCommand

// Data

sealed trait HostedData

case object HC_NOTHING extends HostedData

case class HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE(init: InitHostedChannel) extends HostedData

case class HC_DATA_CLIENT_WAIT_HOST_INIT(refundScriptPubKey: ByteVector) extends HostedData

case class HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE(commitments: HostedCommitments) extends HostedData with HasAbstractCommitments

case class HC_DATA_ESTABLISHED(commitments: HostedCommitments,
                               localError: Option[wire.Error] = None,
                               remoteError: Option[wire.Error] = None,
                               overrideProposal: Option[StateOverride] = None, // CLOSED channel override can be initiated by Host, a new proposed balance should be retained once this happens
                               refundPendingInfo: Option[RefundPending] = None, // Will be present in case if funds should be refunded, but `liabilityDeadlineBlockdays` has not passed yet
                               refundCompleteInfo: Option[String] = None, // Will be present after channel has been manually updated as a refunded one
                               channelUpdate: wire.ChannelUpdate) extends HostedData with HasAbstractCommitments {

  def getError: Option[wire.Error] = localError orElse remoteError
}

object HostedCommitments {
  // Left is locally sent from us to peer, Right is remotely sent from from peer to us
  type LocalOrRemoteUpdate = Either[wire.UpdateMessage, wire.UpdateMessage]
}

case class HostedCommitments(isHost: Boolean,
                             localNodeId: PublicKey,
                             remoteNodeId: PublicKey,
                             channelId: ByteVector32,
                             localSpec: CommitmentSpec,
                             originChannels: Map[Long, Origin],
                             lastCrossSignedState: LastCrossSignedState,
                             futureUpdates: List[LocalOrRemoteUpdate], // For CLOSED channel we need to look here for UpdateFail/Fulfill messages from network for in-flight (client -> we -> network) payments
                             timedOutToPeerHtlcLeftOverIds: Set[Long], // CLOSED channel may have in-flight HTLCs (network -> we -> client), we don't accept peer failure for those and only fail them on timeout
                             fulfilledByPeerHtlcLeftOverIds: Set[Long], // CLOSED channel may have in-flight HTLCs (network -> we -> client) which can later be fulfilled, collect their IDs here
                             announceChannel: Boolean) extends AbstractCommitments {

  val (nextLocalUpdates, nextRemoteUpdates, nextTotalLocal, nextTotalRemote) =
    futureUpdates.foldLeft((List.empty[wire.UpdateMessage], List.empty[wire.UpdateMessage], lastCrossSignedState.localUpdates, lastCrossSignedState.remoteUpdates)) {
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Left(msg)) => (localMessages :+ msg, remoteMessages, totalLocalNumber + 1, totalRemoteNumber)
      case ((localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber), Right(msg)) => (localMessages, remoteMessages :+ msg, totalLocalNumber, totalRemoteNumber + 1)
    }

  val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(localSpec, nextLocalUpdates, nextRemoteUpdates)

  val currentAndNextInFlightHtlcs: Set[DirectedHtlc] = {
    // Failed channel may have HTLCs in current/next commit which are resolved post-closing
    // that is, either they time out and get failed or peer sends a preimage and they get fulfilled
    val postCloseResolvedHtlcIds = timedOutToPeerHtlcLeftOverIds ++ fulfilledByPeerHtlcLeftOverIds
    (localSpec.htlcs ++ nextLocalSpec.htlcs).filterNot(postCloseResolvedHtlcIds contains _.add.id)
  }

  val availableBalanceForSend: MilliSatoshi = nextLocalSpec.toLocal

  val availableBalanceForReceive: MilliSatoshi = nextLocalSpec.toRemote

  val capacity: Satoshi = lastCrossSignedState.initHostedChannel.channelCapacityMsat.truncateToSatoshi

  // Represent incoming cross-signed HTLCs as outgoing from peer's point of view
  // no need to look at next incoming HTLCs here because they are not cross-signed so there was no relay attempt
  def htlcsRemoteCommit: Set[DirectedHtlc] = localSpec.htlcs.collect(DirectedHtlc.incoming).map(OutgoingHtlc)

  def addProposal(update: LocalOrRemoteUpdate): HostedCommitments = copy(futureUpdates = futureUpdates :+ update)

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

  // Meaning sent from us to peer, including the ones yet unsigned by them
  // look into next AND current commit since they may send fail and disconnect
  def timedOutOutgoingHtlcs(blockHeight: Long): Set[wire.UpdateAddHtlc] =
    for {
      OutgoingHtlc(add) <- currentAndNextInFlightHtlcs
      if blockHeight > add.cltvExpiry.toLong
    } yield add

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = {
    val incomingAdds = nextLocalSpec.htlcs.collect(DirectedHtlc.incoming).toList
    val outgoingAdds = nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).toList

    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel, blockDay,
      nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote, incomingAdds, outgoingAdds,
      localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

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
    nextLocalSpec.findOutgoingHtlcById(fulfill.id) match {
      // We do not accept fulfills after payment to peer has been failed (due to timeout so we failed in upstream already)
      case _ if timedOutToPeerHtlcLeftOverIds.contains(fulfill.id) || fulfilledByPeerHtlcLeftOverIds.contains(fulfill.id) => throw UnknownHtlcId(channelId, fulfill.id)
      case Some(htlc) if htlc.add.paymentHash == Crypto.sha256(fulfill.paymentPreimage) => Try((addProposal(Right(fulfill)), originChannels(fulfill.id), htlc.add))
      case Some(_) => Failure(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Failure(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Try[(HostedCommitments, wire.UpdateFailHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(add) => OutgoingPacket.buildHtlcFailure(nodeSecret, cmd, add).map(updateFail => (addProposal(Left(updateFail)), updateFail))
      case None => Failure(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Try[(HostedCommitments, wire.UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & wire.FailureMessageCodecs.BADONION) == 0) {
      Failure(InvalidFailureCode(channelId))
    } else {
      getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(_) =>
          val fail = wire.UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          Success(addProposal(Left(fail)), fail)
        case None => Failure(UnknownHtlcId(channelId, cmd.id))
      }
    }
  }

  def receiveFail(fail: wire.UpdateFailHtlc): Try[(HostedCommitments, Origin, wire.UpdateAddHtlc)] =
    // Unlike Fulfill for Fail/FailMalformed here we make sure they fail our cross-signed outgoing payment
    getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(add) => Try((addProposal(Right(fail)), originChannels(fail.id), add))
      case None => Failure(UnknownHtlcId(channelId, fail.id))
    }

  def receiveFailMalformed(fail: wire.UpdateFailMalformedHtlc): Try[(HostedCommitments, Origin, wire.UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & wire.FailureMessageCodecs.BADONION) == 0) {
      Failure(InvalidFailureCode(channelId))
    } else {
      getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(add) => Try((addProposal(Right(fail)), originChannels(fail.id), add))
        case None => Failure(UnknownHtlcId(channelId, fail.id))
      }
    }
  }
}

case class HostedState(channelId: ByteVector32,
                       nextLocalUpdates: List[wire.UpdateMessage],
                       nextRemoteUpdates: List[wire.UpdateMessage],
                       lastCrossSignedState: LastCrossSignedState)

case class RemoteHostedStateResult(state: HostedState, isLocalSigValid: Boolean)