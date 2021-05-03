package fr.acinq.hc.app.api

import fr.acinq.eclair._
import fr.acinq.bitcoin._
import fr.acinq.hc.app.channel._
import akka.http.scaladsl.server._
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.hc.app.network.{HostedSync, OperationalData}
import akka.actor.{ActorRef, ActorSystem}

import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.hc.app.db.HostedChannelsDb
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.crypto.Mac32
import scala.concurrent.Future
import scodec.bits.ByteVector
import fr.acinq.hc.app.Vals
import akka.util.Timeout
import akka.pattern.ask

// important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541

import fr.acinq.eclair.api.JsonSupport.{formats, marshaller, serialization}


class HCService(kit: Kit, channelsDb: HostedChannelsDb, worker: ActorRef, sync: ActorRef, vals: Vals) extends AbstractService {

  val wallet: BitcoinCoreWallet = kit.wallet.asInstanceOf[BitcoinCoreWallet]

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
      path("findbyremoteid") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_GET_INFO(remoteNodeId))
        }
      } ~
      path("findbysecret") {
        formFields("plainUserSecret".as[String]) { secret =>
          val trimmedUserSecret = ByteVector.view(secret.toLowerCase.trim getBytes "UTF-8")
          channelsDb.getChannelBySecret(Mac32.hmac256(trimmedUserSecret, kit.nodeParams.nodeId.value)) match {
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
      path("hide") {
        formFields(nodeIdFormParam) { remoteNodeId =>
          complete(worker ? HC_CMD_HIDE(remoteNodeId))
        }
      } ~
      path("verifyremotestate") {
        formFields("state".as[ByteVector](binaryDataUnmarshaller)) { state =>
          complete(getHostedStateResult(state))
        }
      } ~
      path("restorefromremotestate") {
        formFields("state".as[ByteVector](binaryDataUnmarshaller)) { state =>
          val RemoteHostedStateResult(remoteState, Some(remoteNodeId), isLocalSigOk) = getHostedStateResult(state)
          require(isLocalSigOk, "Can't proceed: local signature of provided HC state is invalid")
          complete(worker ? HC_CMD_RESTORE(remoteNodeId, remoteState))
        }
      } ~
      path("broadcastpreimages") {
        formFields("preimages".as[List[ByteVector32]](sha256HashesUnmarshaller), "feerateSatByte".as[FeeratePerByte]) { case (preimages, feerateSatByte) =>
          require(feerateSatByte.feerate.toLong > 1, "Preimage broadcast funding feerate must be > 1 sat/byte")
          val broadcastTxId = sendPreimageBroadcast(FeeratePerKw(feerateSatByte), preimages.toSet)
          complete(broadcastTxId)
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

  private def sendPreimageBroadcast(feeRatePerKw: FeeratePerKw, preimages: Set[ByteVector32] = Set.empty): Future[ByteVector32] = {
    val txOuts = preimages.toList.map(_.bytes).map(OP_PUSHDATA.apply).grouped(2).map(OP_RETURN :: _).map(Script.write).map(TxOut(Satoshi(0L), _))
    val tx = Transaction(version = 2, txIn = Nil, txOut = txOuts.toList, lockTime = 0)

    for {
      fundedTx <- wallet.fundTransaction(tx, lockUnspents = false, feeRatePerKw)
      signedTx <- wallet.signTransaction(fundedTx.tx)
      true <- wallet.commit(signedTx.tx)
    } yield signedTx.tx.txid
  }

  private def phcAnnounces(routerData: Router.Data, hostedSyncData: OperationalData) =
    hostedSyncData.phcNetwork.channels.values.map { phc =>
      val node1AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId1)
      val node2AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId2)
      node1AnnounceOpt ++ node2AnnounceOpt
    }

  private def getHostedStateResult(state: ByteVector) = {
    val remoteState = fr.acinq.hc.app.wire.HostedChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
    val remoteNodeIdOpt = Set(remoteState.nodeId1, remoteState.nodeId2).find(pubKey => kit.nodeParams.nodeId != pubKey)
    val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
    RemoteHostedStateResult(remoteState, remoteNodeIdOpt, isLocalSigOk)
  }
}
