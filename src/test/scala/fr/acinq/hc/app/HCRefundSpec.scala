package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CLOSED, NORMAL, OFFLINE}
import fr.acinq.eclair.io.PeerDisconnected
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.hc.app.channel.{HC_CMD_FINALIZE_REFUND, HC_CMD_INIT_PENDING_REFUND, HC_CMD_OVERRIDE_PROPOSE, HC_DATA_ESTABLISHED}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class HCRefundSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Schedule refunding, then finalize") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    // Bob notified Alice about loss of control over channel
    alice ! HC_CMD_INIT_PENDING_REFUND(randomKey.publicKey)
    bob ! alice2bob.expectMsgType[RefundPending]
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundPendingInfo.isDefined)
    alice ! HC_CMD_FINALIZE_REFUND(randomKey.publicKey, info = "Done")
    awaitCond(alice.stateName == NORMAL)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundPendingInfo.isDefined)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundCompleteInfo.isEmpty)
    // Bob disconnects for the first time
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    bob ! alice2bob.expectMsgType[RefundPending] // Bob is notified about pending refunding
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    alice ! HC_CMD_FINALIZE_REFUND(randomKey.publicKey, info = "Done", force = true)
    alice2bob.expectMsgType[wire.Error] // Bob goes offline before receiving error
    awaitCond(alice.stateName == CLOSED)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].errorExt.isEmpty)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundPendingInfo.isEmpty)
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundCompleteInfo.isDefined)
    // Bob disconnects for the second time
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[wire.Error]
    awaitCond(alice.stateName == CLOSED)
    awaitCond(bob.stateName == CLOSED)
    alice ! HC_CMD_OVERRIDE_PROPOSE(randomKey.publicKey, newLocalBalance = 0L.msat) // Does not work for refunded channel
    alice2bob.expectNoMessage()
  }

  test("Schedule refunding, client cancels it by updating a state") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    // Impostor notifies Alice about supposed loss of control over channel
    alice ! HC_CMD_INIT_PENDING_REFUND(randomKey.publicKey)
    bob ! alice2bob.expectMsgType[RefundPending]
    assert(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundPendingInfo.isDefined)
    // Real Bob sees a notification and cancels pending refund by receiving/sending a payment
    val (preimage, alice2bobUpdateAdd) = addHtlcFromAliceToBob(100000L.msat, f)
    fulfillHtlc(alice2bobUpdateAdd.id, preimage, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].errorExt.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundPendingInfo.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].refundCompleteInfo.isEmpty)
  }
}
