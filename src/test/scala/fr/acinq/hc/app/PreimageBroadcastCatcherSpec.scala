package fr.acinq.hc.app

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, Crypto, OP_PUSHDATA, OP_RETURN, Satoshi, Script, Transaction, TxOut}
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

    val catcher = system.actorOf(Props(new PreimageBroadcastCatcher(pdb, Config.vals)))
    val txOuts = preimages.toList.map(_.bytes).map(OP_PUSHDATA.apply).grouped(2).map(OP_RETURN :: _).map(Script.write).map(TxOut(Satoshi(0L), _))
    catcher ! NewTransaction(Transaction(version = 2, txIn = Nil, txOut = txOuts.toList, lockTime = 0))

    val preimage1 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    val preimage2 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    val preimage3 = listener.expectMsgType[PreimageBroadcastCatcher.BroadcastedPreimage].preimage
    listener.expectNoMessage()

    assert(Set(preimage1, preimage2, preimage3) == preimages)
    assert(pdb.findByHash(Crypto.sha256(preimage2)).contains(preimage2))
  }

  test("Extract preimage from serialized tx") {
    val tx = Transaction.read("0200000000010127ab9b94329fb89bd2c323421f5f454d1aad3e8112aafc1e9f36393458e20de0010000001716001405e1e88be50e39f8ead3b5971ec9f166f705e962fdffffff0420a107000000000017a914915511cd50f6efdb459dd3fff3ce6b828d62f2ac870000000000000000436a2003729d8e7a3bfcffc65b4f97df0bdbb128339a7110a1c1b33d8462d1355fc7ba20ca1a2704fdb60b6d5f9ee8b015cbc96fb860c680d63475ec5d8e24beee6144e60000000000000000226a2011bcea37903e543a17ed3e7f754753f1da9c3632cf8deefec96c07839e8c071f6f8006000000000017a914c18cbb698da31d3eb9f6375f68703fad604a9e4e87024730440220324000c4a22770fbb3e68940848dc58f83129823f1c28b97d13e2846a54d9a7f02206349764f5030f86d4b6930cc1ba273b3d09ff8778c13d6eb853f74c398927d270121033cf8ddc4aa0d1ce72ca87adcb85f5bf0ecf6f7b017923da85f58cf0148961a5200000000")

    val preimages: Seq[ByteVector32] = PreimageBroadcastCatcher.extractPreimages(tx)

    val hashes: Seq[ByteVector32] = preimages.map(Crypto sha256 _)

    assert(preimages.map(_.toHex) == List("03729d8e7a3bfcffc65b4f97df0bdbb128339a7110a1c1b33d8462d1355fc7ba", "ca1a2704fdb60b6d5f9ee8b015cbc96fb860c680d63475ec5d8e24beee6144e6", "11bcea37903e543a17ed3e7f754753f1da9c3632cf8deefec96c07839e8c071f"))

    assert(hashes.map(_.toHex) == List("c7c2c21f95025125c327dd349e05e0731ce61003e8d8307a34701d497ba3c603", "698638b5e499833d723ebe03991ddadab4057e64b4c9f56c26c26a58254c03e8", "b4a979578623cb73756647ff000daa4df411bf423753342e78508cea1aae786c"))
  }
}
