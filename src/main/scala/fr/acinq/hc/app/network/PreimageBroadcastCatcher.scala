package fr.acinq.hc.app.network

import fr.acinq.bitcoin._
import scala.concurrent.duration._
import fr.acinq.eclair.blockchain._
import scala.collection.parallel.CollectionConverters._
import fr.acinq.hc.app.network.PreimageBroadcastCatcher._
import fr.acinq.hc.app.{HC, QueryPreimages, ReplyPreimages, Vals}
import fr.acinq.eclair.wire.internal.channel.version3.HCProtocolCodecs
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.io.UnknownMessageReceived
import fr.acinq.hc.app.Tools.DuplicateHandler
import fr.acinq.hc.app.db.PreimagesDb
import org.json4s.JsonAST.JString
import scala.collection.mutable
import scodec.bits.ByteVector
import grizzled.slf4j.Logging
import fr.acinq.eclair.Kit
import akka.actor.Actor
import scodec.Attempt


object PreimageBroadcastCatcher {
  case class SendPreimageBroadcast(feeRatePerKw: FeeratePerKw, preimages: Set[ByteVector32] = Set.empty)

  case class BroadcastedPreimage(hash: ByteVector32, preimage: ByteVector32)

  case object TickClearIpAntiSpam {
    val label = "TickClearIpAntiSpam"
  }

  def extractPreimages(tx: Transaction): Seq[ByteVector32] =
    tx.txOut.map(transactionOutput => Script parse transactionOutput.publicKeyScript).flatMap {
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) :: OP_PUSHDATA(preimage2, 32) :: Nil => List(preimage1, preimage2)
      case OP_RETURN :: OP_PUSHDATA(preimage1, 32) :: Nil => List(preimage1)
      case _ => List.empty[ByteVector]
    }.map(ByteVector32.apply)
}

class PreimageBroadcastCatcher(preimagesDb: PreimagesDb, kit: Kit, vals: Vals) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(1.minute, 1.minute, self, PreimageBroadcastCatcher.TickClearIpAntiSpam)

  context.system.eventStream.subscribe(channel = classOf[NewTransaction], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[NewBlock], subscriber = self)

  lazy val wallet: BitcoinCoreClient = kit.wallet.asInstanceOf[BitcoinCoreClient]
  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty.withDefaultValue(0)
  val emptyUtxo: ByteVector => TxOut = TxOut(Satoshi(0L), _: ByteVector)

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

    case NewBlock(blockHash) =>
      wallet.rpcClient.invoke("getblock", blockHash, 0).foreach {
        case JString(rawBlock) => Block.read(rawBlock).tx.par.flatMap(extractPreimages).foreach(dh.execute)
        case otherwise => logger.error(s"PLGN PHC, PreimageBroadcastCatcher 'getblock' has returned $otherwise")
      }

    case PreimageBroadcastCatcher.TickClearIpAntiSpam => ipAntiSpam.clear

    case PreimageBroadcastCatcher.SendPreimageBroadcast(feeRatePerKw, preimages) =>
      // Each single output may contain up to 2 preimages prefixed by OP_RETURN, a single tx itself may contain many such outputs
      val preimageTxOuts = preimages.toList.map(_.bytes).map(OP_PUSHDATA.apply).grouped(2).map(OP_RETURN :: _).map(Script.write).map(emptyUtxo)
      val txOptions = FundTransactionOptions(feeRatePerKw, lockUtxos = true)

      for {
        balance <- wallet.onChainBalance
        toSend = (balance.confirmed + balance.unconfirmed) / 2
        ourAddress <- wallet.getReceiveAddress("Preimage broadcast")
        pubKeyScript = fr.acinq.eclair.addressToPublicKeyScript(ourAddress, kit.nodeParams.chainHash)
        txOutWithSendBack = TxOut(toSend, Script write pubKeyScript) :: preimageTxOuts.toList
        tx = Transaction(version = 2, txIn = Nil, txOut = txOutWithSendBack, lockTime = 0)
        fundedTx <- wallet.fundTransaction(tx, txOptions)
        signedTx <- wallet.signTransaction(fundedTx.tx)
        true <- wallet.commit(signedTx.tx)
      } sender ! signedTx.tx.txid

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
