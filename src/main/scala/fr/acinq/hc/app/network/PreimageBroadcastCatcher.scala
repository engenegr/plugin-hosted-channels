package fr.acinq.hc.app.network

import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain._
import fr.acinq.hc.app.network.PreimageBroadcastCatcher._
import scala.collection.parallel.CollectionConverters._
import fr.acinq.hc.app.Tools.DuplicateHandler
import fr.acinq.hc.app.db.PreimagesDb
import scodec.bits.ByteVector
import grizzled.slf4j.Logging
import akka.actor.Actor
import scala.util.Try


object PreimageBroadcastCatcher {
  case class BroadcastedPreimage(hash: ByteVector32, preimage: ByteVector32)
}

class PreimageBroadcastCatcher(preimagesDb: PreimagesDb) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[NewTransaction], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[NewBlock], subscriber = self)

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
  }

  def extractPreimages(tx: Transaction): Seq[ByteVector32] =
    tx.txOut.map(transactionOutput => Script parse transactionOutput.publicKeyScript).flatMap {
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) +: OP_PUSHDATA(preimage2, 32) +: Nil => List(preimage1, preimage2)
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) +: Nil => List(preimage1)
      case _ => List.empty[ByteVector]
    }.map(ByteVector32.apply)
}
