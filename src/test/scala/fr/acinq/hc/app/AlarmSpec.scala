package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class AlarmSpec extends AnyFunSuite {
  val master: ExtendedPrivateKey = generate(ByteVector32.fromValidHex("96f2ce974832791629a5878cfd263d75b1a453fe4162d3a77f4c25c47870344f"))
  val publicGraphNodeIds: Set[Crypto.PublicKey] = (0L to 600L).toSet.map((n: Long) => derivePrivateKey(master, hardened(n) :: Nil).publicKey)
  val incompleteGraphNodeIds: Set[Crypto.PublicKey] = publicGraphNodeIds.zipWithIndex.filterNot(_._2 % 3 == 0).map(_._1) // One third is missing
  val blockHash: ByteVector32 = ByteVector32.fromValidHex("0000000000000006e39b741bf8f53934fb58c5aea696beb64aacdd069f82af58") // Earlier than payment CLTV
  val nodeKey: PrivateKey = PrivateKey(ByteVector32.fromValidHex("97f2ce974832791629c5878cfd263d75b1a4a3fe4162d3a77f4ce5c478703445"))

  test("Find closest nodes") {
    val preimage = ByteVector32.fromValidHex("57ca95a6cd7c6e496d4ab622ed7bb13e01ce33dfeb1589f82b570244999eac01")
    val pointOfInterest = Tools.pointOfInterest(nodeKey, blockHash, preimage)
    val closestNodeId = ByteVector.fromValidHex("0269cb331efd826fd8a5ca207bdc476483ca2b99c10c61c4edc267752063fe80a2")
    assert(Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, publicGraphNodeIds).head.value == closestNodeId)
    assert(Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, incompleteGraphNodeIds).head.value == closestNodeId)
  }

  test("Spammer with many nodes still pays") {
    val spammerNodes = (0L to 20L).toSet.map((n: Long) => derivePrivateKey(master, hardened(n) :: hardened(0L) :: Nil).publicKey)
    val allNodes = publicGraphNodeIds ++ spammerNodes

    val allClosestNodes = for {
      preimage <- List.fill(1000)(randomBytes32)
      pointOfInterest = Tools.pointOfInterest(nodeKey, blockHash, preimage)
      closestNodes = Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, allNodes)
    } yield closestNodes.toSet

    val (_, no) = allClosestNodes.map(nodes => nodes.intersect(spammerNodes).nonEmpty).partition(identity)
    assert(no.size > 300) // A spammer with 20 public nodes still pays at least 30% of time in 620 node network
  }

  test("Distance function distribution is ~uniform") {
    val iterations = 10000
    val subNodesSize = 10
    val mean = iterations / subNodesSize
    val allowedVariance = iterations / 50
    val subgraph = Set.fill(subNodesSize)(randomKey.publicKey)

    val allClosestNodes = for {
      preimage <- List.fill(iterations)(randomBytes32)
      pointOfInterest = Tools.pointOfInterest(nodeKey, blockHash, preimage)
      closestNodes = Tools.closestNodes(pointOfInterest.toBitVector.toIndexedSeq, subgraph)
    } yield closestNodes.head

    assert(subgraph == allClosestNodes.toSet)
    assert(allClosestNodes.groupBy(identity).values.map(_.size).forall(v => v < mean + allowedVariance && v > mean - allowedVariance))
  }
}
