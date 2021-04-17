package fr.acinq.hc.app

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{Crypto, OP_PUSHDATA, OP_RETURN, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.NewTransaction
import fr.acinq.eclair.randomBytes32
import fr.acinq.hc.app.db.PreimagesDb
import fr.acinq.hc.app.network.PreimageBroadcastCatcher
import org.scalatest.funsuite.AnyFunSuite


class PreimageBroadcastCatcherSpec extends AnyFunSuite {
  test("Extract preimages from txs, broadcast them and record to db") {
    HCTestUtils.resetEntireDatabase(Config.db)
    val pdb = new PreimagesDb(Config.db)

    val preimages = Set(randomBytes32, randomBytes32, randomBytes32)

    val system = ActorSystem("test")
    val listener = TestProbe()(system)
    system.eventStream.subscribe(listener.ref, classOf[PreimageBroadcastCatcher.BroadcastedPreimage])

    val catcher = system.actorOf(Props(new PreimageBroadcastCatcher(pdb)))
    val txOuts = preimages.toList.map(_.bytes).map(OP_PUSHDATA.apply).grouped(2).map(OP_RETURN :: _).map(Script.write).map(TxOut(Satoshi(0L), _))
    catcher ! NewTransaction(Transaction(version = 2, txIn = Nil, txOut = txOuts.toList, lockTime = 0))

    val preimage1 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    val preimage2 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    val preimage3 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    listener.expectNoMessage()

    assert(Set(preimage1, preimage2, preimage3) == preimages)
    assert(pdb.findByHash(Crypto.sha256(preimage2)).contains(preimage2))
  }
}
