package fr.acinq.hc.app.api

import fr.acinq.eclair._
import fr.acinq.hc.app.channel._
import akka.http.scaladsl.server._
import fr.acinq.eclair.api.FormParamExtractors._

import fr.acinq.eclair.api.JsonSupport.{formats, marshaller, serialization}
import fr.acinq.bitcoin.{Script, ByteVector32}
import akka.actor.{ActorRef, ActorSystem}

import fr.acinq.eclair.api.AbstractService
import scodec.bits.ByteVector
import fr.acinq.hc.app.Vals
import akka.util.Timeout
import akka.pattern.ask


class HCService(kit: Kit, worker: ActorRef, vals: Vals) extends AbstractService {

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
      path("hostedchannel") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_GET_INFO(remoteNodeId))
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
      path("initrefund") {
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
      path("verifystate") {
        formFields("state".as[ByteVector](binaryDataUnmarshaller)) { state =>
          val remoteState = fr.acinq.hc.app.wire.HostedChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
          val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
          complete(RemoteHostedStateResult(remoteState, isLocalSigOk))
        }
      }
    }
  }
}
