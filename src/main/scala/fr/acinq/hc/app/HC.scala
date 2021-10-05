package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.hc.app.HC._
import scala.concurrent.stm._
import fr.acinq.hc.app.channel._
import akka.actor.{ActorRef, ActorSystem, Props}
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Script}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.wire.protocol.{FailureMessage, UpdateAddHtlc}
import fr.acinq.hc.app.db.{Blocking, HostedChannelsDb, HostedUpdatesDb, PreimagesDb}
import fr.acinq.hc.app.network.{HostedSync, OperationalData, PHC, PreimageBroadcastCatcher}
import fr.acinq.eclair.wire.internal.channel.version3.HostedChannelCodecs
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.payment.IncomingPacket
import fr.acinq.bitcoin.Crypto.PublicKey
import akka.http.scaladsl.server.Route
import fr.acinq.eclair.channel.Origin
import fr.acinq.eclair.router.Router
import akka.event.LoggingAdapter
import scala.collection.mutable
import scodec.bits.ByteVector
import akka.util.Timeout
import akka.pattern.ask
import scala.util.Try


object HC {
  final val HC_INVOKE_HOSTED_CHANNEL_TAG = 65535

  final val HC_INIT_HOSTED_CHANNEL_TAG = 65533

  final val HC_LAST_CROSS_SIGNED_STATE_TAG = 65531

  final val HC_STATE_UPDATE_TAG = 65529

  final val HC_STATE_OVERRIDE_TAG = 65527

  final val HC_HOSTED_CHANNEL_BRANDING_TAG = 65525

  final val HC_ANNOUNCEMENT_SIGNATURE_TAG = 65523

  final val HC_RESIZE_CHANNEL_TAG = 65521

  final val HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG = 65519

  final val HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG = 65517

  final val HC_QUERY_PREIMAGES_TAG = 65515

  final val HC_REPLY_PREIMAGES_TAG = 65513

  final val HC_ASK_BRANDING_INFO = 65511


  final val PHC_ANNOUNCE_GOSSIP_TAG = 64513

  final val PHC_ANNOUNCE_SYNC_TAG = 64511

  final val PHC_UPDATE_GOSSIP_TAG = 64509

  final val PHC_UPDATE_SYNC_TAG = 64507


  final val HC_UPDATE_ADD_HTLC_TAG = 63505

  final val HC_UPDATE_FULFILL_HTLC_TAG = 63503

  final val HC_UPDATE_FAIL_HTLC_TAG = 63501

  final val HC_UPDATE_FAIL_MALFORMED_HTLC_TAG = 63499

  final val HC_ERROR_TAG = 63497


  val hostedMessageTags: Set[Int] =
    Set(HC_INVOKE_HOSTED_CHANNEL_TAG, HC_INIT_HOSTED_CHANNEL_TAG, HC_LAST_CROSS_SIGNED_STATE_TAG, HC_STATE_UPDATE_TAG,
      HC_STATE_OVERRIDE_TAG, HC_HOSTED_CHANNEL_BRANDING_TAG, HC_ANNOUNCEMENT_SIGNATURE_TAG, HC_RESIZE_CHANNEL_TAG,
      HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG, HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG, HC_ASK_BRANDING_INFO)

  val preimageQueryTags: Set[Int] = Set(HC_QUERY_PREIMAGES_TAG, HC_REPLY_PREIMAGES_TAG)

  val announceTags: Set[Int] = Set(PHC_ANNOUNCE_GOSSIP_TAG, PHC_ANNOUNCE_SYNC_TAG, PHC_UPDATE_GOSSIP_TAG, PHC_UPDATE_SYNC_TAG)

  val chanIdMessageTags: Set[Int] = Set(HC_UPDATE_ADD_HTLC_TAG, HC_UPDATE_FULFILL_HTLC_TAG, HC_UPDATE_FAIL_HTLC_TAG, HC_UPDATE_FAIL_MALFORMED_HTLC_TAG, HC_ERROR_TAG)

  val remoteNode2Connection: mutable.Map[PublicKey, PeerConnectedWrap] = TMap.empty[PublicKey, PeerConnectedWrap].single
}

class HC extends Plugin with RouteProvider {
  var channelsDb: HostedChannelsDb = _
  var preimageRef: ActorRef = _
  var workerRef: ActorRef = _
  var syncRef: ActorRef = _
  var config: Config = _
  var kit: Kit = _

  override def onSetup(setup: Setup): Unit = {
    config = new Config(datadir = setup.datadir)
    Try(Blocking createTablesIfNotExist config.db)
    channelsDb = new HostedChannelsDb(config.db)
  }

  override def onKit(eclairKit: Kit): Unit = {
    implicit val coreActorSystem: ActorSystem = eclairKit.system
    preimageRef = eclairKit.system actorOf Props(classOf[PreimageBroadcastCatcher], new PreimagesDb(config.db), eclairKit, config.vals)
    syncRef = eclairKit.system actorOf Props(classOf[HostedSync], eclairKit, new HostedUpdatesDb(config.db), config.vals.phcConfig)
    workerRef = eclairKit.system actorOf Props(classOf[Worker], eclairKit, syncRef, preimageRef, channelsDb, config)
    kit = eclairKit
  }

  override def params: PluginParams = new CustomFeaturePlugin with CustomCommitmentsPlugin {

    override def messageTags: Set[Int] = hostedMessageTags ++ preimageQueryTags ++ announceTags ++ chanIdMessageTags

    override def name: String = "Hosted channels"

    override def feature: Feature = HCFeature

    override def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc] = {
      val allHotHtlcs: Seq[DirectedHtlc] = channelsDb.listHotChannels.flatMap(_.commitments.localSpec.htlcs)
      val decryptEither: UpdateAddHtlc => Either[FailureMessage, IncomingPacket] = IncomingPacket.decrypt(_: UpdateAddHtlc, nodeParams.privateKey)(log)
      val resolvePacket: PartialFunction[Either[FailureMessage, IncomingPacket], IncomingHtlc] = PostRestartHtlcCleaner.decryptedIncomingHtlcs(nodeParams.db.payments)
      allHotHtlcs.collect(DirectedHtlc.incoming).map(decryptEither).collect(resolvePacket)
    }

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

  override def route(eclairDirectives: EclairDirectives): Route = {
    import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}
    import scala.concurrent.ExecutionContext.Implicits.global
    import fr.acinq.eclair.api.serde.FormParamExtractors._
    import eclairDirectives._

    val hostedStateUnmarshaller = "state".as[ByteVector](binaryDataUnmarshaller)

    def getHostedStateResult(state: ByteVector) = {
      val remoteState = HostedChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
      val remoteNodeIdOpt = Set(remoteState.nodeId1, remoteState.nodeId2).find(kit.nodeParams.nodeId.!=)
      val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
      RemoteHostedStateResult(remoteState, remoteNodeIdOpt, isLocalSigOk)
    }

    def completeCommand(cmd: HasRemoteNodeIdHostedCommand)(implicit timeout: Timeout) = {
      val futureResponse = (workerRef ? cmd).mapTo[HCCommandResponse]
      complete(futureResponse)
    }

    val invoke: Route = postRequest("hc-invoke") { implicit t =>
      formFields("refundAddress", "secret".as[ByteVector](binaryDataUnmarshaller), nodeIdFormParam) { case (refundAddress, secret, remoteNodeId) =>
        val refundPubkeyScript = Script.write(fr.acinq.eclair.addressToPublicKeyScript(refundAddress, kit.nodeParams.chainHash))
        completeCommand(HC_CMD_LOCAL_INVOKE(remoteNodeId, refundPubkeyScript, secret))
      }
    }

    val externalFulfill: Route = postRequest("hc-externalfulfill") { implicit t =>
      formFields("htlcId".as[Long], "paymentPreimage".as[ByteVector32], nodeIdFormParam) { case (htlcId, paymentPreimage, remoteNodeId) =>
        completeCommand(HC_CMD_EXTERNAL_FULFILL(remoteNodeId, htlcId, paymentPreimage))
      }
    }

    val findByRemoteId: Route = postRequest("hc-findbyremoteid") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_GET_INFO(remoteNodeId))
      }
    }

    val overridePropose: Route = postRequest("hc-overridepropose") { implicit t =>
      formFields("newLocalBalanceMsat".as[MilliSatoshi], nodeIdFormParam) { case (newLocalBalance, remoteNodeId) =>
        completeCommand(HC_CMD_OVERRIDE_PROPOSE(remoteNodeId, newLocalBalance))
      }
    }

    val overrideAccept: Route = postRequest("hc-overrideaccept") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_OVERRIDE_ACCEPT(remoteNodeId))
      }
    }

    val makePublic: Route = postRequest("hc-makepublic") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_PUBLIC(remoteNodeId))
      }
    }

    val makePrivate: Route = postRequest("hc-makeprivate") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_PRIVATE(remoteNodeId))
      }
    }

    val resize: Route = postRequest("hc-resize") { implicit t =>
      formFields("newCapacitySat".as[Satoshi], nodeIdFormParam) { case (newCapacity, remoteNodeId) =>
        completeCommand(HC_CMD_RESIZE(remoteNodeId, newCapacity))
      }
    }

    val suspend: Route = postRequest("hc-suspend") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_SUSPEND(remoteNodeId))
      }
    }

    val verifyRemoteState: Route = postRequest("hc-verifyremotestate") { implicit t =>
      formFields(hostedStateUnmarshaller) { state =>
        complete(getHostedStateResult(state))
      }
    }

    val restoreFromRemoteState: Route = postRequest("hc-restorefromremotestate") { implicit t =>
      formFields(hostedStateUnmarshaller) { state =>
        val RemoteHostedStateResult(remoteState, Some(remoteNodeId), isLocalSigOk) = getHostedStateResult(state)
        require(isLocalSigOk, "Can't proceed: local signature of provided HC state is invalid")
        completeCommand(HC_CMD_RESTORE(remoteNodeId, remoteState))
      }
    }

    val broadcastPreimages: Route = postRequest("hc-broadcastpreimages") { implicit t =>
      formFields("preimages".as[List[ByteVector32]], "feerateSatByte".as[FeeratePerByte]) { case (preimages, feerateSatByte) =>
        require(feerateSatByte.feerate.toLong > 1, "Preimage broadcast funding feerate must be higher than 1 sat/byte")
        val cmd = PreimageBroadcastCatcher.SendPreimageBroadcast(FeeratePerKw(feerateSatByte), preimages.toSet)
        val broadcastTxIdResult = (preimageRef ? cmd).mapTo[ByteVector32]
        complete(broadcastTxIdResult)
      }
    }

    val phcNodes: Route = postRequest("hc-phcnodes") { implicit t =>
      val phcNodeAnnounces = for {
        routerData <- (kit.router ? Router.GetRouterData).mapTo[Router.Data]
        hostedSyncData <- (syncRef ? HostedSync.GetHostedSyncData).mapTo[OperationalData]
      } yield hostedSyncData.phcNetwork.channels.values.toSet.flatMap { phc: PHC =>
        val node1AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId1)
        val node2AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId2)
        node1AnnounceOpt ++ node2AnnounceOpt
      }

      complete(phcNodeAnnounces)
    }

    invoke ~ externalFulfill ~ findByRemoteId ~ overridePropose ~ overrideAccept ~ makePublic ~ makePrivate ~
      resize ~ suspend ~ verifyRemoteState ~ restoreFromRemoteState ~ broadcastPreimages ~ phcNodes
  }
}

case object HCFeature extends Feature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "hosted_channels"
  lazy val mandatory = 32972
}

case object ResizeableHCFeature extends Feature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "resizeable_hosted_channels"
  lazy val mandatory = 32974
}

// Depends on https://github.com/engenegr/eclair-alarmbot-plugin
case class AlmostTimedoutIncomingHtlc(add: wire.protocol.UpdateAddHtlc, fulfill: wire.protocol.UpdateFulfillHtlc, nodeId: PublicKey, blockCount: Long) extends fr.acinq.alarmbot.CustomAlarmBotMessage {
  override def message: String = s"AlmostTimedoutIncomingHtlc, id=${add.id}, amount=${add.amountMsat}, hash=${add.paymentHash}, expiry=${add.cltvExpiry.toLong}, tip=$blockCount, preimage=${fulfill.paymentPreimage}, peer=$nodeId"
  override def senderEntity: String = "HC"
}

case class HCSuspended(nodeId: PublicKey, isHost: Boolean, isLocal: Boolean, description: String) extends fr.acinq.alarmbot.CustomAlarmBotMessage {
  override def message: String = s"HCSuspended, isHost=$isHost, isLocal=$isLocal, peer=$nodeId, description=$description"
  override def senderEntity: String = "HC"
}