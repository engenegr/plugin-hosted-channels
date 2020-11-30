package fr.acinq.hc.app.Channel

import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerDisconnected
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.wire.{ChannelUpdate, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.hc.app.channel.{HC_CMD_OVERRIDE_ACCEPT, HC_CMD_OVERRIDE_PROPOSE, HC_DATA_ESTABLISHED}
import fr.acinq.hc.app.{wire => _, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

class HCOverrideSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Override disregarded in normal state") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999899999L.msat)
    alice2bob.expectNoMessage()
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    bob2alice.expectNoMessage()
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("Override after failure") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage, f)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100000L.msat)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 100000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 9999900000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999900000L.msat)
    val (_, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, ByteVector32.Zeroes) // Wrong preimage
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].localErrors.nonEmpty)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].remoteError.isDefined)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999899999L.msat)
    bob ! alice2bob.expectMsgType[StateOverride].copy(localSigOfRemoteLCSS = ByteVector64.Zeroes) // Wrong signature
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    bob2alice.expectNoMessage()
    // Second try
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999899999L.msat)
    bob ! alice2bob.expectMsgType[StateOverride] // Correct this time
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    alice ! bob2alice.expectMsgType[StateUpdate]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100001L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999899999L.msat)
  }

  test("Override started while client is offline, finished later") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage, f)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100000L.msat)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 100000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 9999900000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999900000L.msat)
    val (_, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, ByteVector32.Zeroes) // Wrong preimage
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999899999L.msat)
    bob ! alice2bob.expectMsgType[StateOverride] // Bob did not get it because he's offline
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].overrideProposal.isEmpty)
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[wire.Error]
    bob ! alice2bob.expectMsgType[StateOverride]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    alice ! bob2alice.expectMsgType[StateUpdate]
    assert(aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]].htlc.paymentHash === alice2bobUpdateAdd1.paymentHash)
    aliceRelayer.expectNoMessage()
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100001L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999899999L.msat)
  }

  test("Override with pending htlc timed out") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage, f)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100000L.msat)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 100000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 9999900000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999900000L.msat)
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, ByteVector32.Zeroes) // Wrong preimage
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    aliceRelayer.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).size === 1)
    alice ! UpdateFailHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, ByteVector.fill(152)(0)) // Fail is disregarded by Alice in CLOSED state
    aliceRelayer.expectNoMessage()
    alice ! CurrentBlockCount(Long.MaxValue)
    bob ! alice2bob.expectMsgType[wire.Error] // One htlc timed out
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // Pending outgoing HTLC is failed upstream
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, preimage1) // Fulfill in CLOSED state after timing out is disregarded by Alice
    bob ! alice2bob.expectMsgType[wire.Error] // Fulfill rejected
    aliceRelayer.expectNoMessage()
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).isEmpty)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999899999L.msat)
    bob ! alice2bob.expectMsgType[StateOverride]
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    alice ! bob2alice.expectMsgType[StateUpdate]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    aliceRelayer.expectNoMessage()
  }

  test("Override with pending htlc fulfilled") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd.id, preimage, f)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 100000L.msat)
    assert(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 100000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal === 9999900000L.msat)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal === 9999900000L.msat)
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(200000L.msat, f, currentBlockHeight)
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, ByteVector32.Zeroes) // Wrong preimage
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    aliceRelayer.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).size === 1)
    alice ! UpdateFulfillHtlc(alice2bobUpdateAdd1.channelId, alice2bobUpdateAdd1.id, preimage1) // Fulfill in CLOSED state is accepted by Alice
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]] // Alice fulfills right away
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.htlcs.collect(DirectedHtlc.outgoing).isEmpty)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.nextLocalSpec.toLocal == 9999700000L.msat)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 9999700000L.msat)
    bob ! alice2bob.expectMsgType[StateOverride]
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    alice ! bob2alice.expectMsgType[StateUpdate]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    aliceRelayer.expectNoMessage()
  }

  test("Override where client has normal state initially, but host has an error") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    alice ! wire.Error(randomBytes32, "Error in offline mode")
    alice2bob.expectNoMessage()
    awaitCond(alice.stateName == OFFLINE)
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == SYNCING)
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState] // First an LCSS in case if Bob has lost channel data
    alice ! bob2alice.expectMsgType[LastCrossSignedState] // Bob does not know about Error yet
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    alice ! HC_CMD_OVERRIDE_PROPOSE(bobKit.nodeParams.nodeId, newLocalBalance = 10000000000L.msat)
    bob ! alice2bob.expectMsgType[StateOverride]
    bob ! HC_CMD_OVERRIDE_ACCEPT(aliceKit.nodeParams.nodeId)
    alice ! bob2alice.expectMsgType[StateUpdate]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    aliceRelayer.expectNoMessage()
  }
}
