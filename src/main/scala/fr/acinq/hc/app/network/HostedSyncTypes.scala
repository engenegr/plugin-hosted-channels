package fr.acinq.hc.app.network


trait HostedSyncState

case object WAIT_FOR_GRAPH extends HostedSyncState

case object WAIT_FOR_HPC_SYNC extends HostedSyncState

case object DOING_HPC_SYNC extends HostedSyncState

case object OPERATIONAL extends HostedSyncState


trait HostedSyncData