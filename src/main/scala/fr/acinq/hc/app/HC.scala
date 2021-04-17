package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.hc.app.HC._
import scala.concurrent.stm._
import akka.actor.{ActorSystem, Props}
import fr.acinq.hc.app.network.{HostedSync, PreimageBroadcastCatcher}
import fr.acinq.hc.app.db.{Blocking, HostedChannelsDb, HostedUpdatesDb, PreimagesDb}
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner
import fr.acinq.eclair.transactions.DirectedHtlc
import scala.concurrent.ExecutionContextExecutor
import fr.acinq.eclair.payment.IncomingPacket
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Origin
import fr.acinq.hc.app.api.HCService
import fr.acinq.bitcoin.ByteVector32
import akka.event.LoggingAdapter
import scala.collection.mutable
import akka.http.scaladsl.Http


object HC {
  final val HC_INVOKE_HOSTED_CHANNEL_TAG = 65535
  final val HC_INIT_HOSTED_CHANNEL_TAG = 65533
  final val HC_LAST_CROSS_SIGNED_STATE_TAG = 65531
  final val HC_STATE_UPDATE_TAG = 65529
  final val HC_STATE_OVERRIDE_TAG = 65527
  final val HC_HOSTED_CHANNEL_BRANDING_TAG = 65525
  final val HC_REFUND_PENDING_TAG = 65523
  final val HC_ANNOUNCEMENT_SIGNATURE_TAG = 65521
  final val HC_RESIZE_CHANNEL_TAG = 65519
  final val HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG = 65517
  final val HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG = 65515

  final val PHC_ANNOUNCE_GOSSIP_TAG = 65513
  final val PHC_ANNOUNCE_SYNC_TAG = 65511
  final val PHC_UPDATE_GOSSIP_TAG = 65509
  final val PHC_UPDATE_SYNC_TAG = 65507

  final val HC_UPDATE_ADD_HTLC_TAG = 65505
  final val HC_UPDATE_FULFILL_HTLC_TAG = 65503
  final val HC_UPDATE_FAIL_HTLC_TAG = 65501
  final val HC_UPDATE_FAIL_MALFORMED_HTLC_TAG = 65499
  final val HC_ERROR_TAG = 65497

  val announceTags: Set[Int] =
    Set(PHC_ANNOUNCE_GOSSIP_TAG, PHC_ANNOUNCE_SYNC_TAG,
      PHC_UPDATE_GOSSIP_TAG, PHC_UPDATE_SYNC_TAG)

  val chanIdMessageTags: Set[Int] =
    Set(HC_UPDATE_ADD_HTLC_TAG, HC_UPDATE_FULFILL_HTLC_TAG,
      HC_UPDATE_FAIL_HTLC_TAG, HC_UPDATE_FAIL_MALFORMED_HTLC_TAG, HC_ERROR_TAG)

  val hostedMessageTags: Set[Int] =
    Set(HC_INVOKE_HOSTED_CHANNEL_TAG, HC_INIT_HOSTED_CHANNEL_TAG, HC_LAST_CROSS_SIGNED_STATE_TAG, HC_STATE_UPDATE_TAG,
      HC_STATE_OVERRIDE_TAG, HC_HOSTED_CHANNEL_BRANDING_TAG, HC_REFUND_PENDING_TAG, HC_ANNOUNCEMENT_SIGNATURE_TAG,
      HC_RESIZE_CHANNEL_TAG, HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG, HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG)

  val remoteNode2Connection: mutable.Map[PublicKey, PeerConnectedWrap] = TMap.empty[PublicKey, PeerConnectedWrap].single

  var clientChannelRemoteNodeIds: Set[PublicKey] = Set.empty
}

class HC extends Plugin {
  var channelsDb: HostedChannelsDb = _
  var preimagesDb: PreimagesDb = _

  override def onSetup(setup: Setup): Unit = {
    // TODO: remove this once slick handles existing indexes correctly
    if (Config.attemptCreateTables) Blocking.createTablesIfNotExist(Config.db)
    channelsDb = new HostedChannelsDb(Config.db)
    preimagesDb = new PreimagesDb(Config.db)
  }

  override def onKit(kit: Kit): Unit = {
    val syncRef = kit.system actorOf Props(classOf[HostedSync], kit, new HostedUpdatesDb(Config.db), Config.vals.phcConfig)
    val workerRef = kit.system actorOf Props(classOf[Worker], kit, syncRef, channelsDb, Config.vals)
    kit.system actorOf Props(classOf[PreimageBroadcastCatcher], preimagesDb)

    implicit val executionContext: ExecutionContextExecutor = kit.system.dispatcher
    implicit val coreActorSystem: ActorSystem = kit.system

    val clientHCs = channelsDb.listClientChannels
    val hcServiceRoute = new HCService(kit, channelsDb, workerRef, syncRef, Config.vals).finalRoute
    require(clientHCs.forall(_.commitments.localNodeId == kit.nodeParams.nodeId), "PLGN PHC, localNodeId mismatch")
    Http().newServerAt(Config.vals.apiParams.bindingIp, Config.vals.apiParams.port).bindFlow(hcServiceRoute)
    HC.clientChannelRemoteNodeIds = clientHCs.map(_.commitments.remoteNodeId).toSet
    workerRef ! Worker.ClientChannels(clientHCs)
  }

  override def params: PluginParams = new CustomFeaturePlugin with ConnectionControlPlugin with CustomCommitmentsPlugin {

    override def forceReconnect(nodeId: PublicKey): Boolean = HC.clientChannelRemoteNodeIds.contains(nodeId)

    override def messageTags: Set[Int] = announceTags ++ chanIdMessageTags ++ hostedMessageTags

    override def name: String = "Hosted channels"

    override def feature: Feature = HCFeature

    override def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc] =
      channelsDb.listHotChannels.flatMap(_.commitments.localSpec.htlcs).collect(DirectedHtlc.incoming)
        .map(incomingUpdateAdd => IncomingPacket.decrypt(incomingUpdateAdd, nodeParams.privateKey)(log))
        .collect(packet => PostRestartHtlcCleaner.decryptedIncomingHtlcs(nodeParams.db.payments)(packet))

    private def htlcsOut = for {
      data <- channelsDb.listHotChannels
      outgoingAdd <- data.pendingHtlcs.collect(DirectedHtlc.outgoing)
      origin <- data.commitments.originChannels.get(outgoingAdd.id)
    } yield (origin, data.commitments.channelId, outgoingAdd.id)

    type PaymentHashAndHtlcId = (ByteVector32, Long)
    type PaymentLocations = Set[PaymentHashAndHtlcId]

    override def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin, PaymentLocations] =
      PostRestartHtlcCleaner.groupByOrigin(htlcsOut, htlcsIn)
  }
}

case object HCFeature extends Feature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "hosted_channels"
  val mandatory = 32772
}