package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerDisconnected
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.{TemporaryNodeFailure, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.hc.app.channel.{HC_CMD_EXTERNAL_FULFILL, HC_CMD_SUSPEND, HC_DATA_ESTABLISHED}
import fr.acinq.hc.app.{HCTestUtils, StateUpdate, Worker}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class HCNormalSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("External fulfill while online") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    alice ! HC_CMD_EXTERNAL_FULFILL(bobKit.nodeParams.nodeId, alice2bobUpdateAdd.id, preimage)
    bob ! alice2bob.expectMsgType[wire.Error]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice2bob.expectNoMessage()
    aliceRelayer.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
  }

  test("External fulfill while offline") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    alice ! HC_CMD_EXTERNAL_FULFILL(bobKit.nodeParams.nodeId, alice2bobUpdateAdd.id, preimage)
    alice2bob.expectMsgType[wire.Error] // Bob does not get it because OFFLINE
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice2bob.expectNoMessage()
    aliceRelayer.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  test("Ordered sequential fulfill of 3 htlcs") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage1, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage1, f)
    val (preimage2, bob2aliceUpdateAdd) = addHtlcFromBob2Alice(100000L.msat, f)
    fulfillBobHtlcByAlice(bob2aliceUpdateAdd.id, preimage2, f)
    val (preimage3, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd1.id, preimage3, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
  }

  test("Unordered sequential fulfill of 2 htlcs") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money
    val (preimage1, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    val (preimage2, bob2aliceUpdateAdd) = addHtlcFromBob2Alice(150000L.msat, f)
    fulfillBobHtlcByAlice(bob2aliceUpdateAdd.id, preimage2, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage1, f)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 150000L.msat)
  }

  test("Bob receives delayed state updates") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money

    val (preimage1, cmd_add1, _) = makeCmdAdd(100000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(100000L.msat, randomKey.publicKey, currentBlockHeight)
    alice ! cmd_add1
    alice ! CMD_SIGN(None)
    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc] // Has not reached bob yet
    val aliceStateUpdate1 = alice2bob.expectMsgType[StateUpdate] // Has not reached bob yet
    bob ! cmd_add2
    bob ! CMD_SIGN(None)
    val bobAdd1 = bob2alice.expectMsgType[UpdateAddHtlc]
    alice ! bobAdd1
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage() // Old update, no repaying
    val aliceStateUpdate2 = alice2bob.expectMsgType[StateUpdate] // Has not reached bob yet
    bob ! aliceAdd1
    bob ! aliceStateUpdate1
    bobRelayer.expectNoMessage() // Old update, no relaying
    bob ! aliceStateUpdate2
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    bob ! CMD_FULFILL_HTLC(aliceAdd1.id, preimage1).copy(commit = true)
    alice ! CMD_FULFILL_HTLC(bobAdd1.id, preimage2).copy(commit = true)
    val bobFulfill1 = bob2alice.expectMsgType[UpdateFulfillHtlc]
    val bobStateUpdate11 = bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    val bobStateUpdate12 = bob2alice.expectMsgType[StateUpdate]
    alice ! bobFulfill1
    alice ! bobStateUpdate11
    alice ! bobStateUpdate12
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("Both Bob and Alice send three htlcs, messages arrive in random order") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money

    val (preimage1, cmd_add1, _) = makeCmdAdd(20000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage2, cmd_add2, _) = makeCmdAdd(20000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage3, cmd_add3, _) = makeCmdAdd(20000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage4, cmd_add4, _) = makeCmdAdd(10000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage5, cmd_add5, _) = makeCmdAdd(10000L.msat, randomKey.publicKey, currentBlockHeight)
    val (preimage6, cmd_add6, _) = makeCmdAdd(10000L.msat, randomKey.publicKey, currentBlockHeight)

    alice ! cmd_add1.copy(commit = true)
    alice ! cmd_add2.copy(commit = true)
    alice ! cmd_add3.copy(commit = true)

    bob ! cmd_add4
    bob ! cmd_add5
    bob ! cmd_add6
    bob ! CMD_SIGN(None)

    val aliceAdd1 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd1
    val bobAdd4 = bob2alice.expectMsgType[UpdateAddHtlc]
    alice ! bobAdd4
    bob ! alice2bob.expectMsgType[StateUpdate]
    val aliceAdd2 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd2
    val bobAdd5 = bob2alice.expectMsgType[UpdateAddHtlc]
    val bobAdd6 = bob2alice.expectMsgType[UpdateAddHtlc]
    alice ! bobAdd5
    alice ! bobAdd6
    bob ! alice2bob.expectMsgType[StateUpdate]
    val aliceAdd3 = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! aliceAdd3
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    bob ! alice2bob.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    bob ! alice2bob.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    bob ! alice2bob.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! CMD_FULFILL_HTLC(bobAdd4.id, preimage4).copy(commit = true)
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    aliceRelayer.expectNoMessage()

    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    bob ! CMD_FULFILL_HTLC(aliceAdd1.id, preimage1).copy(commit = true)
    alice ! CMD_FULFILL_HTLC(bobAdd5.id, preimage5).copy(commit = true)
    bob ! CMD_FULFILL_HTLC(aliceAdd3.id, preimage3).copy(commit = true)
    alice ! CMD_FULFILL_HTLC(bobAdd6.id, preimage6).copy(commit = true)
    bob ! CMD_FULFILL_HTLC(aliceAdd2.id, preimage2).copy(commit = true)
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 230000L.msat)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
  }

  test("Alice sends many HTLC, some time out and some are resolved") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal == 10000000000L.msat)
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    addHtlcFromAliceToBob(300000L.msat, f, currentBlockHeight + 100)
    addHtlcFromAliceToBob(400000L.msat, f, currentBlockHeight + 100)
    alice ! UpdateFailHtlc(randomBytes32, alice2bobUpdateAdd1.id, randomBytes(64)) // Bob fails one HTLC, but does not update state
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size === 3) // Alice takes update into account and waits
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.htlcs.size === 4)
    alice ! wire.Error(randomBytes32, "Error from Bob")
    awaitCond(alice.stateName == CLOSED)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size === 4) // Bob's update without signature has been removed
    alice ! UpdateFailHtlc(randomBytes32, alice2bobUpdateAdd1.id, randomBytes(64)) // Bob tries to fail an HTLC again
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size === 4) // Alice disregards failing in CLOSED state
    alice ! UpdateFulfillHtlc(randomBytes32, alice2bobUpdateAdd1.id, preimage1)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size === 3)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // Fulfill is accepted
    alice ! CurrentBlockCount(currentBlockHeight + 145)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // One HTLC timed out
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size === 2)
    alice ! CurrentBlockCount(currentBlockHeight + 245)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // Two more HTLCS timed out
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal == 9999900000L.msat) // One HTLC was successful
    awaitCond(alice.stateName == CLOSED)
    aliceRelayer.expectNoMessage()
  }

  test("Alice sends many HTLC, all time out while offline, then channel removed") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal == 10000000000L.msat)
    addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight + 100)
    addHtlcFromAliceToBob(300000L.msat, f, currentBlockHeight + 200)
    alice ! CurrentBlockCount(currentBlockHeight + 145)
    alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // One HTLC timed out
    alice ! CurrentBlockCount(currentBlockHeight + 245)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // One HTLC timed out
    awaitCond(alice.stateName == CLOSED)
    alice ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    alice ! Worker.TickRemoveIdleChannels // Has no effect because 1 payment is pending
    alice ! CurrentBlockCount(currentBlockHeight + 345)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // One HTLC timed out
    alice2bob.expectMsgType[wire.Error]
    alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    awaitCond(alice.stateName == OFFLINE)
    alice ! Worker.TickRemoveIdleChannels
    alice2bob.expectTerminated(alice)
  }

  test("Bob fulfills HTLC before cross-signing a state, Alice relays anyway") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, cmd_add, _) = makeCmdAdd(100000L.msat, randomKey.publicKey, currentBlockHeight)
    alice ! cmd_add
    alice ! CMD_SIGN(None)
    val alice2bobUpdateAdd = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! alice2bobUpdateAdd
    bob ! alice2bob.expectMsgType[StateUpdate]
    val bobMissingUpdate = bob2alice.expectMsgType[StateUpdate]
    alice ! UpdateFulfillHtlc(randomBytes32, alice2bobUpdateAdd.id, preimage)
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice ! bobMissingUpdate
    awaitCond(alice.stateName == CLOSED)
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty)
    alice ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    alice ! Worker.TickRemoveIdleChannels
    alice2bob.expectTerminated(alice)
  }

  test("Bob fills a wrong HTLC") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (_, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    alice ! UpdateFulfillHtlc(randomBytes32, alice2bobUpdateAdd1.id, paymentPreimage = randomBytes32)
    awaitCond(alice.stateName == CLOSED)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size == 1)
  }

  test("Pending payments still resolved after manual suspend") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money

    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(10000L.msat, f, currentBlockHeight)
    val (preimage2, bob2AliceUpdateAdd2) = addHtlcFromBob2Alice(10000L.msat, f)
    val (_, bob2AliceUpdateAdd3) = addHtlcFromBob2Alice(10000L.msat, f)

    alice ! HC_CMD_SUSPEND(randomKey.publicKey)
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)

    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd1.id, preimage1)
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice ! CMD_FULFILL_HTLC(bob2AliceUpdateAdd2.id, preimage2)
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bobRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]

    alice ! CMD_FAIL_HTLC(bob2AliceUpdateAdd3.id, Right(TemporaryNodeFailure))
    bob ! alice2bob.expectMsgType[UpdateFailHtlc]

    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty) // Alice state is cleared
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.size == 1) // Bob does not accept fail in CLOSED state

    bob ! CurrentBlockCount(currentBlockHeight + 145)
    alice ! bob2alice.expectMsgType[wire.Error]

    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.isEmpty) // Bob state is cleared too after timeout
  }
}
