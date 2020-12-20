package fr.acinq.hc.app.api

import fr.acinq.eclair._
import fr.acinq.hc.app.channel._
import akka.http.scaladsl.server._
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.api.JsonSupport.{formats, marshaller, serialization}
import fr.acinq.hc.app.network.{HostedSync, OperationalData}
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Script}
import akka.actor.{ActorRef, ActorSystem}

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.hc.app.db.HostedChannelsDb
import fr.acinq.eclair.api.AbstractService
import fr.acinq.eclair.router.Router
import scodec.bits.ByteVector
import fr.acinq.hc.app.Vals
import akka.util.Timeout
import akka.pattern.ask


class HCService(kit: Kit, channelsDb: HostedChannelsDb, worker: ActorRef, sync: ActorRef, vals: Vals) extends AbstractService {

  override val password: String = vals.apiParams.password

  override val actorSystem: ActorSystem = kit.system

  override def route(implicit timeout: Timeout): Route = {
    post {
      path("invoke") {
        formFields(nodeIdFormParam, "refundAddress".as[String], "secret".as[ByteVector](binaryDataUnmarshaller)) { case (remoteNodeId, refundAddress, secret) =>
          complete(worker ? HC_CMD_LOCAL_INVOKE(remoteNodeId, Script.write(fr.acinq.eclair.addressToPublicKeyScript(refundAddress, kit.nodeParams.chainHash)), secret))
        }
      } ~
      path("externalfulfill") {
        formFields(nodeIdFormParam, "htlcId".as[Long], "paymentPreimage".as[ByteVector32](sha256HashUnmarshaller)) { case (remoteNodeId, htlcId, paymentPreimage) =>
          complete(worker ? HC_CMD_EXTERNAL_FULFILL(remoteNodeId, htlcId, paymentPreimage))
        }
      } ~
      path("byremoteid") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_GET_INFO(remoteNodeId))
        }
      } ~
      path("bysecret") {
        formFields("secret".as[ByteVector](binaryDataUnmarshaller)) { secret =>
          channelsDb.getChannelBySecret(secret) match {
            case Some(data) => complete(worker ? HC_CMD_GET_INFO(data.commitments.remoteNodeId))
            case None => complete(s"Could not find and HC with secret: $secret")
          }
        }
      } ~
      path("overridepropose") {
        formFields(nodeIdFormParam, "newLocalBalanceMsat".as[MilliSatoshi]) { case (remoteNodeId, newLocalBalance) =>
          complete(worker ? HC_CMD_OVERRIDE_PROPOSE(remoteNodeId, newLocalBalance))
        }
      } ~
      path("overrideaccept") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_OVERRIDE_ACCEPT(remoteNodeId))
        }
      } ~
      path("startrefund") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_INIT_PENDING_REFUND(remoteNodeId))
        }
      } ~
      path("finalizerefund") {
        formFields(nodeIdFormParam, "info".as[String]) { case (remoteNodeId, info) =>
          complete(worker ? HC_CMD_FINALIZE_REFUND(remoteNodeId, info))
        }
      } ~
      path("makepublic") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_PUBLIC(remoteNodeId))
        }
      } ~
      path("makeprivate") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_PRIVATE(remoteNodeId))
        }
      } ~
      path("resize") {
        formFields(nodeIdFormParam, "newCapacitySat".as[Satoshi]) { case (remoteNodeId, newCapacity) =>
          complete(worker ? HC_CMD_RESIZE(remoteNodeId, newCapacity))
        }
      } ~
      path("suspend") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_SUSPEND(remoteNodeId))
        }
      } ~
      path("drop") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_DROP(remoteNodeId))
        }
      } ~
      path("verifystate") {
        formFields("state".as[ByteVector](binaryDataUnmarshaller)) { state =>
          val remoteState = fr.acinq.hc.app.wire.HostedChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
          val remoteNodeIdOpt = Set(remoteState.nodeId1, remoteState.nodeId2).find(kit.nodeParams.nodeId.!=)
          val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
          val result = RemoteHostedStateResult(remoteState, remoteNodeIdOpt, isLocalSigOk)
          complete(result)
        }
      } ~
      path("phcnodes") {
        val phcNodeAnnounces = for {
          routerData <- (kit.router ? Router.GetRouterData).mapTo[Router.Data]
          hostedSyncData <- (sync ? HostedSync.GetHostedSyncData).mapTo[OperationalData]
        } yield phcAnnounces(routerData, hostedSyncData).flatten.toSet
        complete(phcNodeAnnounces)
      }
    }
  }

  private def phcAnnounces(routerData: Router.Data, hostedSyncData: OperationalData) =
    for {
      phc <- hostedSyncData.phcNetwork.channels.values
      node1AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId1)
      node2AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId2)
    } yield node1AnnounceOpt ++ node2AnnounceOpt
}
