package fr.acinq.hc.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.hc.app.Tools.{DuplicateHandler, DuplicateShortId}
import fr.acinq.hc.app.channel.HC_DATA_ESTABLISHED
import fr.acinq.hc.app.dbo.HostedChannelsDb
import org.scalatest.funsuite.AnyFunSuite

import scala.util.{Failure, Random}


class HostedChannelsDbSpec extends AnyFunSuite {
  test("Insert, then update on second time") {
    import HostedWireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    cdb.updateOrAddNewChannel(data) // Insert
    cdb.updateOrAddNewChannel(data) // Update
    assert(!cdb.getChannelById(hdc.channelId).head.commitments.announceChannel)

    val data1 = data.copy(commitments = hdc.copy(announceChannel = true)) // Channel becomes public

    cdb.updateOrAddNewChannel(data1) // Update
    assert(cdb.getChannelById(hdc.channelId).head.commitments.announceChannel) // channelId is the same, but announce updated

    val data2 = data1.copy(commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32)) // Different remote NodeId, but shortId is the same (which is theoretically possible)

    val insertOrFail = new DuplicateHandler[HC_DATA_ESTABLISHED] {
      def insert(data: HC_DATA_ESTABLISHED): Boolean = cdb.addNewChannel(data)
    }

    assert(cdb.getChannelById(data2.channelId).isEmpty) // Such a channel could not be found
    assert(Failure(DuplicateShortId) === insertOrFail.execute(data2)) // New channel could not be created because of existing shortId

    for (n <- 0 to 10) cdb.updateOrAddNewChannel(data1.copy(commitments = data1.commitments.copy(timedOutToPeerHtlcLeftOverIds = Set(n))))
    assert(cdb.getChannelById(data1.commitments.channelId).get.commitments.timedOutToPeerHtlcLeftOverIds === Set(10L)) // Ten updates in a row
  }

  test("Update secret") {
    import HostedWireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)
    val secret = ByteVector32.Zeroes.bytes

    val hdc1 = hdc.copy(futureUpdates = Nil, originChannels = Map.empty, fulfilledByPeerHtlcLeftOverIds = Set.empty)
    val data1 = data.copy(commitments = hdc1, remoteError = None, refundPendingInfo = None, refundCompleteInfo = None)

    cdb.updateOrAddNewChannel(data1)
    assert(cdb.getChannelBySecret(secret).isEmpty)
    assert(cdb.updateSecretById(data1.channelId, secret))
    assert(cdb.getChannelBySecret(secret).get === data1)
  }

  test("list hot channels (with HTLCs in-flight)") {
    import HostedWireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val data1 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(1L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32))
    val data2 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(2L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32))
    val data3 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(3L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32,
      futureUpdates = Nil, localSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = FeeratePerKw(Satoshi(0L)), toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
        toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue)))))

    cdb.updateOrAddNewChannel(data1)
    cdb.updateOrAddNewChannel(data2)
    cdb.updateOrAddNewChannel(data3)

    assert(cdb.listHotChannels.toSet === Set(data1, data2))
  }

  test("list public channels") {
    import HostedWireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val data1 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(1L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32, announceChannel = true))
    val data2 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(2L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32, announceChannel = true))
    val data3 = data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(3L)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32))

    cdb.addNewChannel(data1)
    cdb.addNewChannel(data2)
    cdb.addNewChannel(data3)

    assert(cdb.listPublicChannels.isEmpty)

    cdb.updateOrAddNewChannel(data1)
    cdb.updateOrAddNewChannel(data2)
    cdb.updateOrAddNewChannel(data3)

    assert(cdb.listPublicChannels.toSet === Set(data1, data2))
  }

  test("Processing 1000 hot channels") {
    import HostedWireSpec._
    HCTestUtils.resetEntireDatabase()
    val cdb = new HostedChannelsDb(Config.db)

    val hdcs = for (n <- 0L to 1000L) yield data.copy(channelUpdate = channelUpdate.copy(shortChannelId = ShortChannelId(n)), commitments = hdc.copy(remoteNodeId = randomKey.publicKey, channelId = randomBytes32))
    hdcs.foreach(cdb.updateOrAddNewChannel)

    val a = System.currentTimeMillis()
    assert(cdb.listHotChannels.size === 1001)
    assert(System.currentTimeMillis() - a < 1000L) // less than 1 ms per object
  }
}
