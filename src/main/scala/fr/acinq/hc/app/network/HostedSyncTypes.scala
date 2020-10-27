package fr.acinq.hc.app.network

import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Router.PublicChannel
import scala.collection.immutable.SortedMap
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId

// STATE

sealed trait HostedSyncState

case object WAIT_FOR_NORMAL_NETWORK_DATA extends HostedSyncState

case object WAIT_FOR_PHC_SYNC extends HostedSyncState

case object DOING_PHC_SYNC extends HostedSyncState

case object OPERATIONAL extends HostedSyncState

// DATA

sealed trait HostedSyncData {
  def phcNetwork: PHCNetwork
}

case class WaitForNormalNetworkData(phcNetwork: PHCNetwork) extends HostedSyncData

case class OperationalData(phcNetwork: PHCNetwork,
                           phcGossip: CollectedGossip,
                           lastSyncNodeId: Option[PublicKey],
                           normalChannels: SortedMap[ShortChannelId, PublicChannel],
                           normalGraph: DirectedGraph) extends HostedSyncData