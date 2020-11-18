package fr.acinq.hc.app

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.{Kit, TestConstants}
import fr.acinq.eclair.channel.{LocalChannelDown, LocalChannelUpdate, NORMAL, SYNCING, State}
import fr.acinq.eclair.io.{ConnectionInfo, PeerConnected}
import fr.acinq.eclair.wire.{AnnouncementMessage, ChannelUpdate, HasChannelId, UnknownMessage}
import fr.acinq.hc.app.channel._
import fr.acinq.hc.app.dbo.HostedChannelsDb
import org.scalatest.{FixtureTestSuite, ParallelTestExecution}
import slick.jdbc.PostgresProfile

import scala.collection.mutable

trait HCStateTestsHelperMethods extends TestKitBase with FixtureTestSuite with ParallelTestExecution {

  case class SetupFixture(alice: TestFSMRef[State, HostedData, HostedChannel],
                          bob: TestFSMRef[State, HostedData, HostedChannel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          aliceSync: TestProbe,
                          bobSync: TestProbe,
                          aliceKit: Kit,
                          bobKit: Kit,
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
    val aliceKit = HCTestUtils.testKit(TestConstants.Alice.nodeParams)
    val bobKit = HCTestUtils.testKit(TestConstants.Bob.nodeParams)

    val aliceDB: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.aliceRelationalDb", Config.config)
    val bobDB: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.bobRelationalDb", Config.config)

    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val aliceSync = TestProbe()
    val bobSync = TestProbe()

    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])

    val alicePeerConnected = PeerConnected(bob2alice.ref, aliceKit.nodeParams.nodeId, ConnectionInfo(new InetSocketAddress("127.0.0.2", 9001), TestProbe().ref, localInit = null, remoteInit = null))
    val bobPeerConnected = PeerConnected(alice2bob.ref, bobKit.nodeParams.nodeId, ConnectionInfo(new InetSocketAddress("127.0.0.3", 9001), TestProbe().ref, localInit = null, remoteInit = null))
    val aliceConnections: mutable.Map[PublicKey, PeerConnectedWrap] = mutable.Map(bobKit.nodeParams.nodeId -> PeerConnectedWrapTest(bobPeerConnected))
    val bobConnections: mutable.Map[PublicKey, PeerConnectedWrap] = mutable.Map(aliceKit.nodeParams.nodeId -> PeerConnectedWrapTest(alicePeerConnected))

    val alice: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(aliceKit, aliceConnections, bobKit.nodeParams.nodeId, new HostedChannelsDb(aliceDB, aliceKit.nodeParams.nodeId), aliceSync.ref, Config.vals))
    val bob: TestFSMRef[State, HostedData, HostedChannel] = TestFSMRef(new HostedChannel(bobKit, bobConnections, aliceKit.nodeParams.nodeId, new HostedChannelsDb(bobDB, bobKit.nodeParams.nodeId), bobSync.ref, Config.vals))

    alice2bob.watch(alice)
    bob2alice.watch(bob)

    SetupFixture(alice, bob, alice2bob, bob2alice, aliceSync, bobSync, aliceKit, bobKit, aliceDB, bobDB, channelUpdateListener)
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
}
