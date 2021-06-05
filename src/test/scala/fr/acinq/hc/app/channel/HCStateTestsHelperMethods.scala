package fr.acinq.hc.app.channel

import java.net.InetSocketAddress
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.{ConnectionInfo, PeerConnected}
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.protocol.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.protocol.{AnnouncementMessage, ChannelUpdate, HasChannelId, UnknownMessage, UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiryDelta, Kit, MilliSatoshi, TestConstants, randomBytes32}
import fr.acinq.hc.app.db.HostedChannelsDb
import fr.acinq.hc.app._
import org.scalatest.{FixtureTestSuite, ParallelTestExecution}
import slick.jdbc.PostgresProfile

trait HCStateTestsHelperMethods extends TestKitBase with FixtureTestSuite with ParallelTestExecution {

  case class SetupFixture(alice: TestFSMRef[State, HostedData, HostedChannel],
                          bob: TestFSMRef[State, HostedData, HostedChannel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          aliceSync: TestProbe,
                          bobSync: TestProbe,
                          aliceKit: Kit,
                          bobKit: Kit,
                          aliceRelayer: TestProbe,
                          bobRelayer: TestProbe,
                          aliceDB: PostgresProfile.backend.Database,
                          bobDB: PostgresProfile.backend.Database,
                          channelUpdateListener: TestProbe) {
    def currentBlockHeight: Long = aliceKit.nodeParams.currentBlockHeight
  }

  case class PeerConnectedWrapTest(info: PeerConnected) extends PeerConnectedWrap { me =>
    def sendHasChannelIdMsg(message: HasChannelId): Unit = info.peer ! message
    def sendHostedChannelMsg(message: HostedChannelMessage): Unit = info.peer ! message
    def sendRoutingMsg(message: AnnouncementMessage): Unit = info.peer ! message
    def sendUnknownMsg(message: UnknownMessage): Unit = info.peer ! message
    lazy val remoteIp: Array[Byte] = info.connectionInfo.address.getAddress.getAddress
  }

  def init(): SetupFixture = {
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val (aliceKit, aliceRelayer) = HCTestUtils.testKit(TestConstants.Alice.nodeParams)
    val (bobKit, bobRelayer) = HCTestUtils.testKit(TestConstants.Bob.nodeParams)

    val aliceDB: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.aliceRelationalDb", HCTestUtils.config.config)
    val bobDB: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.bobRelationalDb", HCTestUtils.config.config)

    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val aliceSync = TestProbe()
    val bobSync = TestProbe()

    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[AlmostTimedoutIncomingHtlc])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])

    val alicePeerConnected = PeerConnected(bob2alice.ref, aliceKit.nodeParams.nodeId, ConnectionInfo(new InetSocketAddress("127.0.0.2", 9001), TestProbe().ref, localInit = null, remoteInit = null))
    val bobPeerConnected = PeerConnected(alice2bob.ref, bobKit.nodeParams.nodeId, ConnectionInfo(new InetSocketAddress("127.0.0.3", 9001), TestProbe().ref, localInit = null, remoteInit = null))
    HC.remoteNode2Connection addOne aliceKit.nodeParams.nodeId -> PeerConnectedWrapTest(alicePeerConnected)
    HC.remoteNode2Connection addOne bobKit.nodeParams.nodeId -> PeerConnectedWrapTest(bobPeerConnected)
    val alice: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(aliceKit, bobKit.nodeParams.nodeId, new HostedChannelsDb(aliceDB), aliceSync.ref, HCTestUtils.config))
    val bob: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(bobKit, aliceKit.nodeParams.nodeId, new HostedChannelsDb(bobDB), bobSync.ref, HCTestUtils.config))

    alice2bob.watch(alice)
    bob2alice.watch(bob)

    SetupFixture(alice, bob, alice2bob, bob2alice, aliceSync, bobSync, aliceKit, bobKit, aliceRelayer, bobRelayer, aliceDB, bobDB, channelUpdateListener)
  }

  def reachNormal(setup: SetupFixture): Unit = {
    import setup._
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
    alice ! bob2alice.expectMsgType[StateUpdate]
    awaitCond(alice.stateData.isInstanceOf[HC_DATA_ESTABLISHED])
    bob ! alice2bob.expectMsgType[StateUpdate]
    awaitCond(bob.stateData.isInstanceOf[HC_DATA_ESTABLISHED])
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    awaitCond(!channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.isNode1)
    awaitCond(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.isNode1)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
  }

  def announcePHC(setup: SetupFixture): Unit = {
    import setup._
    bob ! HC_CMD_PUBLIC(aliceKit.nodeParams.nodeId, force = true)
    alice ! bob2alice.expectMsgType[AnnouncementSignature]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    alice2bob.expectNoMessage() // Alice does not react since she did not issue a local HC_CMD_PUBLIC command
    alice ! HC_CMD_PUBLIC(bobKit.nodeParams.nodeId, force = true)
    bob ! alice2bob.expectMsgType[AnnouncementSignature]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    bobSync.expectMsgType[UnknownMessage]
    bobSync.expectMsgType[UnknownMessage]
    alice ! bob2alice.expectMsgType[AnnouncementSignature]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    aliceSync.expectMsgType[UnknownMessage]
    aliceSync.expectMsgType[UnknownMessage]
    channelUpdateListener.expectNoMessage()
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    aliceSync.expectNoMessage()
    bobSync.expectNoMessage()
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = randomBytes32,
                 upstream: Upstream = Upstream.Local(UUID.randomUUID), replyTo: TestProbe = TestProbe()): (ByteVector32, CMD_ADD_HTLC, TestProbe) = {
    val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(replyTo.ref, upstream, paymentHash, ChannelHop(null, destination, null) :: Nil,
      FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (paymentPreimage, cmd, replyTo)
  }

  def addHtlcFromAliceToBob(amount: MilliSatoshi, setup: SetupFixture, blockHeight: Long): (ByteVector32, UpdateAddHtlc) = {
    import setup._
    val (preimage, cmd_add, replyListener) = makeCmdAdd(amount, bobKit.nodeParams.nodeId, blockHeight)
    alice ! cmd_add
    alice ! CMD_SIGN(None)
    replyListener.expectMsgType[RES_SUCCESS[_]]
    val alice2bobUpdateAdd = alice2bob.expectMsgType[UpdateAddHtlc]
    bob ! alice2bobUpdateAdd
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    bobRelayer.expectMsgType[Relayer.RelayForward]
    bobRelayer.expectNoMessage()
    (preimage, alice2bobUpdateAdd)
  }

  def fulfillAliceHtlcByBob(htlcId: Long, preimage: ByteVector32, setup: SetupFixture): Unit = {
    import setup._
    bob ! CMD_FULFILL_HTLC(htlcId, preimage)
    bob ! CMD_SIGN(None)
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    aliceRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
  }

  def addHtlcFromBob2Alice(amount: MilliSatoshi, setup: SetupFixture): (ByteVector32, UpdateAddHtlc) = {
    import setup._
    val (preimage, cmd_add, replyListener) = makeCmdAdd(amount, aliceKit.nodeParams.nodeId, currentBlockHeight)
    bob ! cmd_add
    bob ! CMD_SIGN(None)
    replyListener.expectMsgType[RES_SUCCESS[_]]
    val bob2aliceUpdateAdd = bob2alice.expectMsgType[UpdateAddHtlc]
    alice ! bob2aliceUpdateAdd
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    aliceRelayer.expectMsgType[Relayer.RelayForward]
    aliceRelayer.expectNoMessage()
    (preimage, bob2aliceUpdateAdd)
  }

  def fulfillBobHtlcByAlice(htlcId: Long, preimage: ByteVector32, setup: SetupFixture): Unit = {
    import setup._
    alice ! CMD_FULFILL_HTLC(htlcId, preimage)
    alice ! CMD_SIGN(None)
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bobRelayer.expectMsgType[RES_ADD_SETTLED[_, _]]
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
  }
}
