package fr.acinq.hc.app.network

import scala.concurrent.duration._
import fr.acinq.hc.app.network.HostedSync._
import fr.acinq.hc.app.{PHCConfig, PeerConnectedWrap, QueryPublicHostedChannels}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Kit}
import fr.acinq.hc.app.dbo.{HostedUpdatesDb, PHC}
import scala.util.{Failure, Random}

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.wire.ChannelAnnouncement
import fr.acinq.eclair.router.SyncProgress
import scala.concurrent.Future


object HostedSync {
  case object RefreshRouterData { val label = "RefreshRouterData" }

  case object GetPeersForSync { val label = "GetPeersForSync" }

  case class PeersToGetSyncFrom(peers: Seq[PeerConnectedWrap] = Nil)

  case class TickSendGossip(peers: Seq[PeerConnectedWrap] = Nil)

  case class SendSyncTo(info: PeerConnectedWrap)
}

class HostedSync(kit: Kit, db: HostedUpdatesDb, phcConfig: PHCConfig) extends FSMDiagnosticActorLogging[HostedSyncState, HostedSyncData] {
  startWith(stateData = WaitForNormalNetworkData(db.getState), stateName = WAIT_FOR_NORMAL_NETWORK_DATA)
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

  when(WAIT_FOR_PHC_SYNC) {
    case Event(PeersToGetSyncFrom(peers), data: OperationalData) =>
      val connectedPublicPeers: Seq[PeerConnectedWrap] = publicPeers(peers, data)
      val randomPeers: Seq[PeerConnectedWrap] = Random.shuffle(connectedPublicPeers)

      randomPeers.headOption match {
        case Some(publicPeerConnectedWrap) =>
          log.info(s"PLGN PHC, HostedSync, sync with nodeId=${publicPeerConnectedWrap.info.nodeId.toString}")
          publicPeerConnectedWrap sendMsg QueryPublicHostedChannels(kit.nodeParams.chainHash)
          goto(DOING_HPC_SYNC)

        case None =>
          // A frequent ask timer has been scheduled in transition
          log.info("PLGN PHC, HostedSync, no PHC peers, waiting")
          stay
      }
  }

  when(DOING_HPC_SYNC, stateTimeout = 20.minutes) {
    case Event(StateTimeout, data: OperationalData) =>
      val data1 = WaitForNormalNetworkData(data.phcNetwork)
      log.info("PLGN PHC, HostedSync, sync timeout, retrying")
      goto(WAIT_FOR_PHC_SYNC) using data1

      // TODO: peer sends end right away
    case _ => stay
  }

  when(OPERATIONAL) {
    case _ => stay
  }

  whenUnhandled {
    case Event(RefreshRouterData, _: OperationalData) =>
      kit.router ! Symbol("data")
      stay

    case Event(routerData: fr.acinq.eclair.router.Router.Data, data: OperationalData) =>
      val data1 = data.copy(normalChannels = routerData.channels, normalGraph = routerData.graph)
      log.info("PLGN PHC, HostedSync, updated normal network data")
      stay using data1

    // Process in separate thread to prevent queue slowdown
    case Event(TickSendGossip(peers), data: OperationalData) =>

      Future {
        val currentPublicPeers: Seq[PeerConnectedWrap] = publicPeers(peers, data)
        val allUpdates = data.phcGossip.updates1.values ++ data.phcGossip.updates2.values
        log.info(s"PLGN HC, TickBroadcast, gossiping to peers num=${currentPublicPeers.size}")

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
        case Failure(err) => log.info(s"PLGN HC, TickBroadcast, fail, error=${err.getMessage}")
        case _ => log.info(s"PLGN HC, TickBroadcast, success, ${data.phcGossip.asString}")
      }

      val emptyGossip = CollectedGossip(Map.empty)
      val data1 = data.copy(phcGossip = emptyGossip)
      stay using data1

    case Event(SendSyncTo(wrap), data: OperationalData) =>
      Future(data.phcNetwork.channels.values.flatMap(_.sendList) foreach wrap.sendUnknownMsg) onComplete {
        case Failure(err) => log.info(s"PLGN HC, SendSyncTo, fail, peer=${wrap.info.nodeId.toString} error=${err.getMessage}")
        case _ => log.info(s"PLGN HC, SendSyncTo, success, peer=${wrap.info.nodeId.toString}")
      }

      stay
  }

  onTransition {
    case WAIT_FOR_NORMAL_NETWORK_DATA -> WAIT_FOR_PHC_SYNC =>
      context.system.eventStream.unsubscribe(channel = classOf[SyncProgress], subscriber = self)
      // We always ask for full sync on startup, keep asking frequently if no PHC peer is connected yet
      startTimerWithFixedDelay(RefreshRouterData.label, RefreshRouterData, 10.minutes)
      startTimerWithFixedDelay(GetPeersForSync.label, GetPeersForSync, 2.minutes)
      context.parent ! GetPeersForSync

    case WAIT_FOR_PHC_SYNC -> DOING_HPC_SYNC =>
      // Once PHC sync is started we schedule a less frequent periodic re-sync (timer is restarted)
      startTimerWithFixedDelay(GetPeersForSync.label, GetPeersForSync, PHC.tickRequestFullSyncThreshold)

    case DOING_HPC_SYNC -> WAIT_FOR_PHC_SYNC =>
      // PHC sync has been taking too long, try again with hopefully another PHC peer
      startTimerWithFixedDelay(GetPeersForSync.label, GetPeersForSync, 2.minutes)
      context.parent ! GetPeersForSync
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
}
