package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.hc.app.Tools.{DuplicateHandler, DuplicateShortId}
import fr.acinq.hc.app.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.hc.app.dbo.HostedChannelsDb
import org.scalatest.funsuite.AnyFunSuite
import scala.util.{Failure, Random}


class ChannelsDbSpec extends AnyFunSuite {
  test("Insert, then update on second time") {
    import WireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    cdb.updateOrAddNewChannel(hdc) // Insert
    cdb.updateOrAddNewChannel(hdc) // Update
    assert(!cdb.getChannelById(hdc.channelId).head.announceChannel)

    val hdc1 = hdc.copy(announceChannel = true) // Channel becomes public

    cdb.updateOrAddNewChannel(hdc1) // Update
    assert(cdb.getChannelById(hdc.channelId).head.announceChannel) // channelId is the same, but announce updated

    val hdc2 = hdc.copy(remoteNodeId = randomKey.publicKey) // Different remote NodeId, but shortId is the same (which is theoretically possible)

    val insertOrFail = new DuplicateHandler[HOSTED_DATA_COMMITMENTS] {
      def insert(data: HOSTED_DATA_COMMITMENTS): Boolean = cdb.addNewChannel(data)
    }

    assert(cdb.getChannelById(hdc2.channelId).isEmpty) // Such a channel could not be found
    assert(Failure(DuplicateShortId) === insertOrFail.execute(hdc2)) // New channel could not be created because of existing shortId

    for (n <- 0 to 10) cdb.updateOrAddNewChannel(hdc1.copy(failedToPeerHtlcLeftoverIds = Set(n)))
    assert(cdb.getChannelById(hdc1.channelId).get.failedToPeerHtlcLeftoverIds === Set(10L)) // Ten updates in a row
  }

  test("Update secret") {
    import WireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)
    val secret = ByteVector32.Zeroes.bytes

    val hdc1 = hdc.copy(futureUpdates = Nil, originChannels = Map.empty, remoteError = None, fulfilledByPeerHtlcLeftoverIds = Set.empty, refundPendingInfo = None, refundCompleteInfo = None)

    cdb.updateOrAddNewChannel(hdc1)
    assert(cdb.getChannelBySecret(secret).isEmpty)
    assert(cdb.updateSecretById(hdc.channelId, secret))
    assert(cdb.getChannelBySecret(secret).get === hdc1)
  }

  test("list hot channels (with HTLCs in-flight)") {
    import WireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(1L)), remoteNodeId = randomKey.publicKey)
    val hdc2 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(2L)), remoteNodeId = randomKey.publicKey)
    val hdc3 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(3L)), remoteNodeId = randomKey.publicKey,
      futureUpdates = Nil, localSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(Satoshi(0L)), toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
        toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))))

    cdb.updateOrAddNewChannel(hdc1)
    cdb.updateOrAddNewChannel(hdc2)
    cdb.updateOrAddNewChannel(hdc3)

    assert(cdb.listHotChannels.toSet === Set(hdc1, hdc2))
  }

  test("list public channels") {
    import WireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(1L)), remoteNodeId = randomKey.publicKey, announceChannel = true)
    val hdc2 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(2L)), remoteNodeId = randomKey.publicKey, announceChannel = true)
    val hdc3 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(3L)), remoteNodeId = randomKey.publicKey)

    cdb.addNewChannel(hdc1)
    cdb.addNewChannel(hdc2)
    cdb.addNewChannel(hdc3)

    assert(cdb.listPublicChannels.isEmpty)

    cdb.updateOrAddNewChannel(hdc1)
    cdb.updateOrAddNewChannel(hdc2)
    cdb.updateOrAddNewChannel(hdc3)

    assert(cdb.listPublicChannels.toSet === Set(hdc1, hdc2))
  }

  test("Processing 1000 hot channels") {
    import WireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val hdcs = for (n <- 0L to 1000L) yield hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(n)), remoteNodeId = randomKey.publicKey)
    hdcs.foreach(cdb.updateOrAddNewChannel)

    val a = System.currentTimeMillis()
    assert(cdb.listHotChannels.size === 1001)
    assert(System.currentTimeMillis() - a < 1000L) // less than 1 ms per object
  }
}
