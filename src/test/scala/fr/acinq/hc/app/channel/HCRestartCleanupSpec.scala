package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.channel.{CLOSED, CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.wire.protocol.{TemporaryNodeFailure, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.hc.app.db.HostedChannelsDb
import fr.acinq.hc.app.{HC, HCTestUtils}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class HCRestartCleanupSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("List pending incoming HTLCs for NORMAL and CLOSED channel") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val bobHC: HC = new HC {
      channelsDb = new HostedChannelsDb(bobDB)
    }

    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (preimage2, alice2bobUpdateAdd2) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (_, alice2bobUpdateAdd4) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)

    assert(bobHC.params.asInstanceOf[CustomCommitmentsPlugin].getIncomingHtlcs(bobKit.nodeParams, null).map(_.add).toSet == Set(alice2bobUpdateAdd1, alice2bobUpdateAdd2, alice2bobUpdateAdd3, alice2bobUpdateAdd4))

    fulfillAliceHtlcByBob(alice2bobUpdateAdd1.id, preimage1, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd2.id, preimage2, f)

    assert(bobHC.params.asInstanceOf[CustomCommitmentsPlugin].getIncomingHtlcs(bobKit.nodeParams, null).map(_.add).toSet == Set(alice2bobUpdateAdd3, alice2bobUpdateAdd4))

    val (_, cmd_add5, _) = makeCmdAdd(1000000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add5
    bob ! alice2bob.expectMsgType[UpdateAddHtlc]

    assert(bobHC.params.asInstanceOf[CustomCommitmentsPlugin].getIncomingHtlcs(bobKit.nodeParams, null).map(_.add).toSet == Set(alice2bobUpdateAdd3, alice2bobUpdateAdd4)) // Non-cross-signed remote add is disregarded

    alice ! HC_CMD_SUSPEND(randomKey.publicKey)
    bob ! alice2bob.expectMsgType[wire.protocol.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)

    assert(bobHC.params.asInstanceOf[CustomCommitmentsPlugin].getIncomingHtlcs(bobKit.nodeParams, null).map(_.add).toSet == Set(alice2bobUpdateAdd3, alice2bobUpdateAdd4)) // Unsigned Alice add is removed

    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd3.id, preimage3) // This won't have an effect because `getIncomingHtlcs` looks at localSpec, but this is irrelevant (HC will disregard and send fail back)

    bob ! CMD_FAIL_HTLC(alice2bobUpdateAdd4.id, Right(TemporaryNodeFailure)) // This will have an effect because `pendingHtlcs` will become empty so channel is not treated as Hot any more

    awaitCond(bobHC.params.asInstanceOf[CustomCommitmentsPlugin].getIncomingHtlcs(bobKit.nodeParams, null).map(_.add).isEmpty)
  }

  test("List pending outgoing HTLCs for NORMAL and CLOSED channel") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val aliceHC: HC = new HC {
      channelsDb = new HostedChannelsDb(aliceDB)
    }

    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (preimage2, alice2bobUpdateAdd2) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (preimage3, alice2bobUpdateAdd3) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (_, alice2bobUpdateAdd4) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)

    val List(id1, id2, id3, id4) = List(alice2bobUpdateAdd1.id, alice2bobUpdateAdd2.id, alice2bobUpdateAdd3.id, alice2bobUpdateAdd4.id)
    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id1, id2, id3, id4))

    fulfillAliceHtlcByBob(alice2bobUpdateAdd1.id, preimage1, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd2.id, preimage2, f)

    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id3, id4))

    val (_, cmd_add5, _) = makeCmdAdd(1000000L.msat, bobKit.nodeParams.nodeId, currentBlockHeight)
    alice ! cmd_add5
    val aliceAdd5 = alice2bob.expectMsgType[UpdateAddHtlc]

    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id3, id4, aliceAdd5.id)) // Pending outgoing is taken into account (we could have signed)

    alice ! HC_CMD_SUSPEND(randomKey.publicKey)
    bob ! alice2bob.expectMsgType[wire.protocol.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)

    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id3, id4, aliceAdd5.id))

    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd3.id, preimage3)
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]

    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id4, aliceAdd5.id)) // Fulfill in CLOSED is accepted

    bob ! CMD_FAIL_HTLC(alice2bobUpdateAdd4.id, Right(TemporaryNodeFailure))
    alice ! bob2alice.expectMsgType[UpdateFailHtlc]

    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).toSet == Set(id4, aliceAdd5.id)) // Remote fail in CLOSED (disregarded)

    alice ! CurrentBlockHeight(currentBlockHeight + 145)
    awaitCond(aliceHC.params.asInstanceOf[CustomCommitmentsPlugin].getHtlcsRelayedOut(Nil, bobKit.nodeParams, null).values.flatten.map(_._2).isEmpty) // Local fake fail of outgoing payment on timeout
  }
}
