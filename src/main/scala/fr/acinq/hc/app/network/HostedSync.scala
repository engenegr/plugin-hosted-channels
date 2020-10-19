package fr.acinq.hc.app.network

import fr.acinq.hc.app.HC._
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.hc.app.network.HostedSync._
import fr.acinq.hc.app.{PHCConfig, PeerConnectedWrap, QueryPublicHostedChannels, ReplyPublicHostedChannelsEnd}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, UnknownMessage}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Kit}
import fr.acinq.hc.app.dbo.{Blocking, HostedUpdatesDb}
import scala.util.{Failure, Random, Try}

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.router.SyncProgress
import fr.acinq.hc.app.Tools.isNode1
import fr.acinq.hc.app.wire.Codecs
import scala.concurrent.Future
import scodec.Attempt


object HostedSync {
  case object RefreshRouterData { val label = "RefreshRouterData" }

  case object GetPeersForSync { val label = "GetPeersForSync" }

  case class PeersToGetSyncFrom(peers: Seq[PeerConnectedWrap] = Nil)

  case class TickSendGossip(peers: Seq[PeerConnectedWrap] = Nil)

  case class SendSyncTo(info: PeerConnectedWrap)
}

class HostedSync(kit: Kit, updatesDb: HostedUpdatesDb, phcConfig: PHCConfig) extends FSMDiagnosticActorLogging[HostedSyncState, HostedSyncData] {
  startWith(stateData = WaitForNormalNetworkData(updatesDb.getState), stateName = WAIT_FOR_NORMAL_NETWORK_DATA)
  context.system.eventStream.subscribe(channel = classOf[SyncProgress], subscriber = self)

  when(WAIT_FOR_NORMAL_NETWORK_DATA) {
    case Event(SyncProgress(1D), _) =>
      kit.router ! Symbol("data")
      stay

    case Event(routerData: fr.acinq.eclair.router.Router.Data, data: WaitForNormalNetworkData) =>
      val data1 = OperationalData(data.phcNetwork, CollectedGossip(Map.empty), routerData.channels, routerData.graph)
      log.info("PLGN PHC, HostedSync, got normal network data")
      goto(WAIT_FOR_PHC_SYNC) using data1
  }

  /**
   * WAIT_FOR_PHC_SYNC state has no specific events for now,
   * used for scheduling a frequent GetPeersForSync in transitions
   */

  when(DOING_PHC_SYNC, stateTimeout = 10.minutes) {
    case Event(StateTimeout, data: OperationalData) =>
      log.info("PLGN PHC, HostedSync, sync timeout, rescheduling")
      goto(WAIT_FOR_PHC_SYNC) using WaitForNormalNetworkData(data.phcNetwork)

    case Event(sync @ UnknownMessage(PHC_ANNOUNCE_SYNC_TAG | PHC_UPDATE_SYNC_TAG, _), data: OperationalData) =>
      Codecs.decodeAnnounceMessage(sync) match {
        case Attempt.Successful(msg: ChannelAnnouncement)
          if data.phcNetwork.channels.contains(msg.shortChannelId) && data.phcNetwork.isAnnounceAcceptable(msg) =>
          stay using data.copy(phcNetwork = data.phcNetwork withUpdatedAnnounce msg)

        case Attempt.Successful(msg: ChannelAnnouncement)
          if isNewAnnounceAcceptable(msg, data) && data.phcNetwork.isNewAnnounceAcceptable(msg, phcConfig) =>
          stay using data.copy(phcNetwork = data.phcNetwork withNewAnnounce msg)

        case Attempt.Successful(msg: ChannelUpdate)
          if isUpdateAcceptable(msg, data) && data.phcNetwork.isUpdateAcceptable(msg) =>
          stay using data.copy(phcNetwork = data.phcNetwork withUpdate msg)

        case Attempt.Successful(something) =>
          log.info(s"PLGN PHC, HostedSync, got unacceptable message=$something")
          stay

        case Attempt.Failure(err) =>
          log.info(s"PLGN PHC, HostedSync, parsing fail=${err.message}")
          stay
      }

    case Event(ReplyPublicHostedChannelsEnd, data: OperationalData) =>
      // Note that in case of db failure sync announces are retained, this is fine
      val phcNetwork1 = data.phcNetwork.copy(messagesReceived = Set.empty)

      tryAddGossipToDb(data.phcNetwork) match {
        case _ if data.phcNetwork.messagesReceived.isEmpty =>
          log.info(s"PLGN PHC, HostedSync, peer has sent nothing, rescheduling")
          goto(WAIT_FOR_PHC_SYNC) using WaitForNormalNetworkData(phcNetwork1)

        case Failure(err) =>
          log.info(s"PLGN PHC, HostedSync, db fail=${err.getMessage}")
          goto(WAIT_FOR_PHC_SYNC) using WaitForNormalNetworkData(phcNetwork1)

        case _ =>
          log.info(s"PLGN PHC, HostedSync, successful db update")
          goto(OPERATIONAL) using data.copy(phcNetwork = phcNetwork1)
      }
  }

  when(OPERATIONAL) {
    case _ => stay
  }

  whenUnhandled {

    // PERIODIC ROUTER DATA REFRESH

    case Event(RefreshRouterData, _: OperationalData) =>
      kit.router ! Symbol("data")
      stay

    case Event(routerData: fr.acinq.eclair.router.Router.Data, data: OperationalData) =>
      val data1 = data.copy(normalChannels = routerData.channels, normalGraph = routerData.graph)
      log.info("PLGN PHC, HostedSync, updated normal network data")
      stay using data1

    // SEND OUT GOSSIP AND SYNC

    // Process in separate thread to prevent queue slowdown
    case Event(TickSendGossip(peers), data: OperationalData) =>

      Future {
        val currentPublicPeers: Seq[PeerConnectedWrap] = publicPeers(peers, data)
        val allUpdates = data.phcGossip.updates1.values ++ data.phcGossip.updates2.values
        log.info(s"PLGN PHC, TickBroadcast, gossiping to peers num=${currentPublicPeers.size}")

        for {
          (_, wrap) <- data.phcGossip.announces
          publicPeerConnectedWrap <- currentPublicPeers
          if !wrap.seenFrom.contains(publicPeerConnectedWrap.info.nodeId)
        } publicPeerConnectedWrap sendGossipMsg wrap.announcement

        for {
          wrap <- allUpdates
          publicPeerConnectedWrap <- currentPublicPeers
          if !wrap.seenFrom.contains(publicPeerConnectedWrap.info.nodeId)
        } publicPeerConnectedWrap sendGossipMsg wrap.update
      } onComplete {
        case Failure(err) => log.info(s"PLGN PHC, TickBroadcast, fail, error=${err.getMessage}")
        case _ => log.info(s"PLGN PHC, TickBroadcast, success, ${data.phcGossip.asString}")
      }

      val emptyGossip = CollectedGossip(Map.empty)
      val data1 = data.copy(phcGossip = emptyGossip)
      stay using data1

    case Event(SendSyncTo(wrap), data: OperationalData) =>
      Future(data.phcNetwork.channels.values.flatMap(_.announcesList) foreach wrap.sendUnknownMsg) onComplete {
        case Failure(err) => log.info(s"PLGN PHC, SendSyncTo, fail, peer=${wrap.info.nodeId.toString} error=${err.getMessage}")
        case _ => log.info(s"PLGN PHC, SendSyncTo, success, peer=${wrap.info.nodeId.toString}")
      }

      stay

    // PERIODIC SYNC

    case Event(GetPeersForSync, _) =>
      context.parent ! GetPeersForSync
      stay

    case Event(PeersToGetSyncFrom(peers), data: OperationalData) =>
      val connectedPublicPeers: Seq[PeerConnectedWrap] = publicPeers(peers, data)
      val randomPeers: Seq[PeerConnectedWrap] = Random.shuffle(connectedPublicPeers)

      randomPeers.headOption match {
        case Some(publicPeerConnectedWrap) =>
          log.info(s"PLGN PHC, HostedSync, sync with nodeId=${publicPeerConnectedWrap.info.nodeId.toString}")
          publicPeerConnectedWrap sendMsg QueryPublicHostedChannels(kit.nodeParams.chainHash)
          goto(DOING_PHC_SYNC)

        case None =>
          // A frequent ask timer has been scheduled in transition
          log.info("PLGN PHC, HostedSync, no PHC peers, waiting")
          stay
      }
  }

  onTransition {
    case WAIT_FOR_NORMAL_NETWORK_DATA -> WAIT_FOR_PHC_SYNC =>
      context.system.eventStream.unsubscribe(channel = classOf[SyncProgress], subscriber = self)
      // We always ask for full sync on startup, keep asking frequently if no PHC peer is connected yet
      startTimerWithFixedDelay(RefreshRouterData.label, RefreshRouterData, 10.minutes)
      self ! GetPeersForSync
  }

  onTransition {
    case _ -> WAIT_FOR_PHC_SYNC =>
      // PHC sync has been taking too long, try again with hopefully another PHC peer
      startTimerWithFixedDelay(GetPeersForSync.label, GetPeersForSync, 2.minutes)

    case WAIT_FOR_PHC_SYNC -> _ =>
      // Once PHC sync is started we schedule a less frequent periodic re-sync (timer is restarted)
      startTimerWithFixedDelay(GetPeersForSync.label, GetPeersForSync, PHC.tickRequestFullSyncThreshold)
  }

  initialize()

  def publicPeers(peers: Seq[PeerConnectedWrap], data: OperationalData): Seq[PeerConnectedWrap] =
    peers.filter(wrap => data.normalGraph.getIncomingEdgesOf(wrap.info.nodeId).nonEmpty)

  // These checks require router and graph data
  def isNewAnnounceAcceptable(announce: ChannelAnnouncement, data: OperationalData): Boolean = {
    val node1HasEnoughIncomingChans = data.normalGraph.getIncomingEdgesOf(announce.nodeId1).count(_.desc.a != announce.nodeId2) >= phcConfig.minNormalChans
    val node2HasEnoughIncomingChans = data.normalGraph.getIncomingEdgesOf(announce.nodeId2).count(_.desc.a != announce.nodeId1) >= phcConfig.minNormalChans
    !data.normalChannels.contains(announce.shortChannelId) && node1HasEnoughIncomingChans && node2HasEnoughIncomingChans
  }

  def isUpdateAcceptable(update: ChannelUpdate, data: OperationalData): Boolean =
    update.htlcMaximumMsat.exists(maxHostedCap => phcConfig.minCapacity < maxHostedCap) &&
      data.phcNetwork.channels.get(update.shortChannelId).exists(_ verifySig update)

  private def tryAddGossipToDb(network: PHCNetwork) = Try {
    Blocking.txWrite(DBIO.sequence(network.messagesReceived.map {
      case message: ChannelAnnouncement => updatesDb.addAnnounce(message)
      case message: ChannelUpdate if isNode1(message) => updatesDb.addUpdate1(message)
      case message: ChannelUpdate => updatesDb.addUpdate2(message)
    }.toSeq), updatesDb.db)
  }
}
