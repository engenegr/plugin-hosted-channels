package fr.acinq.hc.app.channel

import akka.actor.FSM.StateTimeout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PeerDisconnected
import fr.acinq.eclair.{TestKitBaseClass, wire}
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.hc.app.channel._
import fr.acinq.hc.app._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class HCEstablishmentSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Successful HC creation") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val bobData = bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED]
    val aliceData = alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED]
    assert(!bobData.commitments.isHost)
    assert(aliceData.commitments.isHost)
    assert(bobData.commitments.lastCrossSignedState.verifyRemoteSig(Alice.nodeParams.nodeId))
    assert(aliceData.commitments.lastCrossSignedState.verifyRemoteSig(Bob.nodeParams.nodeId))
  }

  test("State timeout closes unestablished channels") { f =>
    import f._
    alice ! Worker.HCPeerConnected
    awaitCond(alice.stateName == SYNCING)
    alice ! StateTimeout
    alice2bob.expectTerminated(alice)
  }

  test("Host rejects an invalid refundScriptPubKey") { f =>
    import f._
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    awaitCond(bob.stateName == SYNCING)
    awaitCond(alice.stateName == SYNCING)
    bob ! HC_CMD_LOCAL_INVOKE(aliceKit.nodeParams.nodeId, refundScriptPubKey = ByteVector32.Zeroes, ByteVector32.Zeroes)
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_CLIENT_WAIT_HOST_INIT])
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[wire.Error]
    alice2bob.expectTerminated(alice)
    bob2alice.expectTerminated(bob)
  }

  test("Disconnect in a middle of establishment, then retry") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    awaitCond(bob.stateName == SYNCING)
    awaitCond(alice.stateName == SYNCING)
    bob ! HC_CMD_LOCAL_INVOKE(aliceKit.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey, ByteVector32.Zeroes)
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_CLIENT_WAIT_HOST_INIT])
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    awaitCond(alice.stateData.isInstanceOf[HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE])
    bob ! alice2bob.expectMsgType[InitHostedChannel]
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE])
    bob2alice.expectMsgType[StateUpdate] // This message does not reach Alice so channel is not persisted
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    alice2bob.expectTerminated(alice)
    bob2alice.expectTerminated(bob)

    // Retry
    val f2 = init()
    reachNormal(f2)
  }

  test("Host rejects a channel with duplicate id") { f =>
    HCTestUtils.resetEntireDatabase(f.aliceDB)
    HCTestUtils.resetEntireDatabase(f.bobDB)
    reachNormal(f)

    val f2 = init()
    import f2._
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    awaitCond(bob.stateName == SYNCING)
    awaitCond(alice.stateName == SYNCING)
    bob ! HC_CMD_LOCAL_INVOKE(aliceKit.nodeParams.nodeId, Bob.channelParams.defaultFinalScriptPubKey, ByteVector32.Zeroes)
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_CLIENT_WAIT_HOST_INIT])
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    awaitCond(alice.stateData.isInstanceOf[HC_DATA_HOST_WAIT_CLIENT_STATE_UPDATE])
    bob ! alice2bob.expectMsgType[InitHostedChannel]
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_CLIENT_WAIT_HOST_STATE_UPDATE])
    alice ! bob2alice.expectMsgType[StateUpdate] // Alice tries to persist a channel, but fails because of duplicate
    bob ! alice2bob.expectMsgType[wire.Error]
    alice2bob.expectTerminated(alice)
    bob2alice.expectTerminated(bob)
  }

  test("Remove stale channels without commitments") { f =>
    import f._
    alice ! Worker.TickRemoveIdleChannels
    bob ! Worker.TickRemoveIdleChannels
    alice2bob.expectTerminated(alice)
    bob2alice.expectTerminated(bob)
  }

  test("Remove stale channels with commitments") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    alice ! Worker.TickRemoveIdleChannels
    bob ! Worker.TickRemoveIdleChannels
    alice2bob.expectTerminated(alice)
    awaitCond(bob.stateName == OFFLINE) // Client stays offline
  }

  test("Re-establish a channel") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    channelUpdateListener.expectMsgType[LocalChannelDown]
    channelUpdateListener.expectMsgType[LocalChannelDown]
    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectMsgType[ChannelUpdate]
  }
}
