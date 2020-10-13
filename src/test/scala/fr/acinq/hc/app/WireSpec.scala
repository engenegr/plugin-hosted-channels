package fr.acinq.hc.app

import fr.acinq.eclair._
import org.scalatest.funsuite.AnyFunSuite


class WireSpec extends AnyFunSuite {
  test("Correctly derive HC id and short id") {
    val pubkey1 = randomKey.publicKey.value
    val pubkey2 = randomKey.publicKey.value
    assert(Tools.hostedChanId(pubkey1, pubkey2) === Tools.hostedChanId(pubkey2, pubkey1))
    assert(Tools.hostedShortChanId(pubkey1, pubkey2) === Tools.hostedShortChanId(pubkey2, pubkey1))
  }
}
