package fr.acinq.hc.app.api

import fr.acinq.eclair._
import fr.acinq.bitcoin._
import fr.acinq.hc.app.channel._
import akka.http.scaladsl.server._
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.hc.app.network.{HostedSync, OperationalData, PHC}
import akka.actor.{ActorRef, ActorSystem}

import fr.acinq.eclair.wire.internal.channel.version2.HostedChannelCodecs
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.api.AbstractService
import fr.acinq.eclair.router.Router
import scala.concurrent.Future
import scodec.bits.ByteVector
import fr.acinq.hc.app.Vals
import akka.util.Timeout
import akka.pattern.ask


class HCService(kit: Kit, worker: ActorRef, sync: ActorRef, vals: Vals) extends AbstractService {

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  private val hostedStateUnmarshaller = "state".as[ByteVector](binaryDataUnmarshaller)

  private val wallet = kit.wallet.asInstanceOf[BitcoinCoreWallet]

  override implicit val actorSystem: ActorSystem = kit.system

  override val password: String = vals.apiParams.password

  val invoke: Route = postRequest("invoke") { implicit t =>
    formFields("refundAddress", "secret".as[ByteVector](binaryDataUnmarshaller), nodeIdFormParam) { case (refundAddress, secret, remoteNodeId) =>
      val refundPubkeyScript = Script.write(fr.acinq.eclair.addressToPublicKeyScript(refundAddress, kit.nodeParams.chainHash))
      completeCommand(HC_CMD_LOCAL_INVOKE(remoteNodeId, refundPubkeyScript, secret))
    }
  }

  val externalFulfill: Route = postRequest("externalfulfill") { implicit t =>
    formFields("htlcId".as[Long], "paymentPreimage".as[ByteVector32], nodeIdFormParam) { case (htlcId, paymentPreimage, remoteNodeId) =>
      completeCommand(HC_CMD_EXTERNAL_FULFILL(remoteNodeId, htlcId, paymentPreimage))
    }
  }

  val findByRemoteId: Route = postRequest("findbyremoteid") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_GET_INFO(remoteNodeId))
    }
  }

  val overridePropose: Route = postRequest("overridepropose") { implicit t =>
    formFields("newLocalBalanceMsat".as[MilliSatoshi], nodeIdFormParam) { case (newLocalBalance, remoteNodeId) =>
      completeCommand(HC_CMD_OVERRIDE_PROPOSE(remoteNodeId, newLocalBalance))
    }
  }

  val overrideAccept: Route = postRequest("overrideaccept") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_OVERRIDE_ACCEPT(remoteNodeId))
    }
  }

  val makePublic: Route = postRequest("makepublic") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_PUBLIC(remoteNodeId))
    }
  }

  val makePrivate: Route = postRequest("makeprivate") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_PRIVATE(remoteNodeId))
    }
  }

  val resize: Route = postRequest("resize") { implicit t =>
    formFields("newCapacitySat".as[Satoshi], nodeIdFormParam) { case (newCapacity, remoteNodeId) =>
      completeCommand(HC_CMD_RESIZE(remoteNodeId, newCapacity))
    }
  }

  val suspend: Route = postRequest("suspend") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_SUSPEND(remoteNodeId))
    }
  }

  val hide: Route = postRequest("hide") { implicit t =>
    formFields(nodeIdFormParam) { remoteNodeId =>
      completeCommand(HC_CMD_HIDE(remoteNodeId))
    }
  }

  val verifyRemoteState: Route = postRequest("verifyremotestate") { implicit t =>
    formFields(hostedStateUnmarshaller) { state =>
      complete(getHostedStateResult(state))
    }
  }

  val restoreFromRemoteState: Route = postRequest("restorefromremotestate") { implicit t =>
    formFields(hostedStateUnmarshaller) { state =>
      val RemoteHostedStateResult(remoteState, Some(remoteNodeId), isLocalSigOk) = getHostedStateResult(state)
      require(isLocalSigOk, "Can't proceed: local signature of provided HC state is invalid")
      completeCommand(HC_CMD_RESTORE(remoteNodeId, remoteState))
    }
  }

  val broadcastPreimages: Route = postRequest("broadcastpreimages") { implicit t =>
    formFields("preimages".as[List[ByteVector32]], "feerateSatByte".as[FeeratePerByte]) { case (preimages, feerateSatByte) =>
      require(feerateSatByte.feerate.toLong > 1, "Preimage broadcast funding feerate must be > 1 sat/byte")
      val broadcastTxId = sendPreimageBroadcast(FeeratePerKw(feerateSatByte), preimages.toSet)
      complete(broadcastTxId)
    }
  }

  val phcNodes: Route = postRequest("phcnodes") { implicit t =>
    val phcNodeAnnounces = for {
      routerData <- (kit.router ? Router.GetRouterData).mapTo[Router.Data]
      hostedSyncData <- (sync ? HostedSync.GetHostedSyncData).mapTo[OperationalData]
    } yield hostedSyncData.phcNetwork.channels.values.toSet.flatMap { phc: PHC =>
      val node1AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId1)
      val node2AnnounceOpt = routerData.nodes.get(phc.channelAnnounce.nodeId2)
      node1AnnounceOpt ++ node2AnnounceOpt
    }

    complete(phcNodeAnnounces)
  }

  val compoundRoute: Route = securedHandler {
    invoke ~ externalFulfill ~ findByRemoteId  ~ overridePropose ~ overrideAccept ~ makePublic ~ makePrivate ~
      resize ~ suspend ~ hide ~ verifyRemoteState ~ restoreFromRemoteState ~ broadcastPreimages ~ phcNodes
  }

  private def completeCommand(cmd: HasRemoteNodeIdHostedCommand)(implicit timeout: Timeout) = {
    val futureResponse = (worker ? cmd).mapTo[HCCommandResponse]
    complete(futureResponse)
  }

  private def sendPreimageBroadcast(feeRatePerKw: FeeratePerKw, preimages: Set[ByteVector32] = Set.empty): Future[ByteVector32] = {
    val txOuts = preimages.toList.map(_.bytes).map(OP_PUSHDATA.apply).grouped(2).map(OP_RETURN :: _).map(Script.write).map(TxOut(Satoshi(0L), _))
    val tx = Transaction(version = 2, txIn = Nil, txOut = txOuts.toList, lockTime = 0)

    for {
      fundedTx <- wallet.fundTransaction(tx, lockUtxos = false, feeRatePerKw)
      signedTx <- wallet.signTransaction(fundedTx.tx)
      true <- wallet.commit(signedTx.tx)
    } yield signedTx.tx.txid
  }

  private def getHostedStateResult(state: ByteVector) = {
    val remoteState = HostedChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
    val remoteNodeIdOpt = Set(remoteState.nodeId1, remoteState.nodeId2).find(kit.nodeParams.nodeId.!=)
    val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
    RemoteHostedStateResult(remoteState, remoteNodeIdOpt, isLocalSigOk)
  }
}
