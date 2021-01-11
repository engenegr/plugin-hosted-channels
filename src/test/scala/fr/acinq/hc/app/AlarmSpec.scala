package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.bitcoin.Crypto.PrivateKey
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class AlarmSpec extends AnyFunSuite {
  val master: ExtendedPrivateKey = generate(ByteVector32.fromValidHex("96f2ce974832791629a5878cfd263d75b1a453fe4162d3a77f4c25c47870344f"))
  val publicGraphNodeIds: Set[Crypto.PublicKey] = (0L to 600L).toSet.map((n: Long) => derivePrivateKey(master, hardened(n) :: Nil).publicKey)
  val incompleteGraphNodeIds: Set[Crypto.PublicKey] = publicGraphNodeIds.zipWithIndex.filterNot(_._2 % 3 == 0).map(_._1) // One third is missing

  test("Find closest nodes") {
    val blockHash = ByteVector32.fromValidHex("0000000000000006e39b741bf8f53934fb58c5aea696beb64aacdd069f82af58") // Earlier than payment CLTV
    val preimage = ByteVector32.fromValidHex("57ca95a6cd7c6e496d4ab622ed7bb13e01ce33dfeb1589f82b570244999eac01")
    val nodeKey = PrivateKey(ByteVector32.fromValidHex("97f2ce974832791629c5878cfd263d75b1a4a3fe4162d3a77f4ce5c478703445"))
    val pointOfInterest = Tools.pointOfInterest(nodeKey, blockHash, preimage)
    val closestNodeId = ByteVector.fromValidHex("020ff52abb64e2edb8df5d68609e17800755137810d7c9240ed1ac7e98999b29f9")
    assert(Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, publicGraphNodeIds).head.value == closestNodeId)
    assert(Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, incompleteGraphNodeIds).head.value == closestNodeId)
  }

  test("Spammer with many nodes still pays") {
    val spammerNodes = (0L to 20L).toSet.map((n: Long) => derivePrivateKey(master, hardened(n) :: hardened(0L) :: Nil).publicKey)
    val blockHash = ByteVector32.fromValidHex("0000000000000006e39b741bf8f53934fb58c5aea696beb64aacdd069f82af58") // Earlier than payment CLTV
    val nodeKey = PrivateKey(ByteVector32.fromValidHex("97f2ce974832791629c5878cfd263d75b1a4a3fe4162d3a77f4ce5c478703445"))
    val allNodes = publicGraphNodeIds ++ spammerNodes

    val allClosestNodes = for {
      preimage <- List.fill(1000)(randomBytes32)
      pointOfInterest = Tools.pointOfInterest(nodeKey, blockHash, preimage)
      closestNodes = Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, allNodes)
    } yield closestNodes.toSet

    val (_, no) = allClosestNodes.map(nodes => nodes.intersect(spammerNodes).nonEmpty).partition(identity)
    assert(no.size > 300) // A spammer with 20 public nodes still pays at least 30% of time in 620 node network
  }
}
