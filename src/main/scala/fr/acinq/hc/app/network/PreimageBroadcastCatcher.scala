package fr.acinq.hc.app.network

import fr.acinq.bitcoin._

import scala.concurrent.duration._
import fr.acinq.eclair.blockchain._

import scala.collection.parallel.CollectionConverters._
import fr.acinq.hc.app.network.PreimageBroadcastCatcher._
import fr.acinq.hc.app.{HC, QueryPreimages, ReplyPreimages, Vals}

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.io.UnknownMessageReceived
import fr.acinq.hc.app.Tools.DuplicateHandler
import fr.acinq.hc.app.db.PreimagesDb

import scala.collection.mutable
import scodec.bits.ByteVector
import grizzled.slf4j.Logging
import akka.actor.Actor
import fr.acinq.eclair.wire.internal.channel.version3.HCProtocolCodecs
import scodec.Attempt


object PreimageBroadcastCatcher {
  case class BroadcastedPreimage(hash: ByteVector32, preimage: ByteVector32)

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  def extractPreimages(tx: Transaction): Seq[ByteVector32] =
    tx.txOut.map(transactionOutput => Script parse transactionOutput.publicKeyScript).flatMap {
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) :: OP_PUSHDATA(preimage2, 32) :: Nil => List(preimage1, preimage2)
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) :: Nil => List(preimage1)
      case _ => List.empty[ByteVector]
    }.map(ByteVector32.apply)
}

class PreimageBroadcastCatcher(preimagesDb: PreimagesDb, vals: Vals) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(1.minute, 1.minute, self, PreimageBroadcastCatcher.TickClearIpAntiSpam)

  context.system.eventStream.subscribe(channel = classOf[NewTransaction], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[NewBlock], subscriber = self)

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty withDefaultValue 0

  private val dh =
    new DuplicateHandler[ByteVector32] {
      def insert(supposedlyPreimage: ByteVector32): Boolean = {
        val paymentHash: ByteVector32 = Crypto.sha256(supposedlyPreimage)
        context.system.eventStream publish BroadcastedPreimage(paymentHash, supposedlyPreimage)
        preimagesDb.addPreimage(paymentHash, supposedlyPreimage)
      }
    }

  override def receive: Receive = {
    case NewTransaction(tx) => extractPreimages(tx).foreach(dh.execute)

    case NewBlock(block) => block.tx.par.flatMap(extractPreimages).foreach(dh.execute)

    case PreimageBroadcastCatcher.TickClearIpAntiSpam => ipAntiSpam.clear

    case msg: UnknownMessageReceived =>
      Tuple3(HCProtocolCodecs decodeHostedMessage msg.message, HC.remoteNode2Connection get msg.nodeId, preimagesDb) match {
        case (Attempt.Successful(query: QueryPreimages), Some(wrap), db) if ipAntiSpam(wrap.remoteIp) < vals.maxPreimageRequestsPerIpPerMinute =>
          val foundPreimages = query.hashes.take(10).flatMap(db.findByHash)
          wrap sendHostedChannelMsg ReplyPreimages(foundPreimages)
          // Record this request for anti-spam
          ipAntiSpam(wrap.remoteIp) += 1

        case (Attempt.Successful(_: QueryPreimages), Some(wrap), _) =>
          logger.info(s"PLGN PHC, PreimageBroadcastCatcher, too many preimage requests, peer=${msg.nodeId}")
          wrap sendHostedChannelMsg ReplyPreimages(Nil)

        case (Attempt.Successful(some), _, _) =>
          logger.info(s"PLGN PHC, PreimageBroadcastCatcher, got unrelated message=${some.getClass.getName}, peer=${msg.nodeId}")

        case _ =>
          logger.info(s"PLGN PHC, PreimageBroadcastCatcher, could not parse a message with tag=${msg.message.tag}, peer=${msg.nodeId}")
      }
  }
}
