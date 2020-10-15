package fr.acinq.hc.app

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair._
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.{Block, ByteVector64, Crypto}
import fr.acinq.eclair.router.Announcements
import fr.acinq.hc.app.dbo.{Blocking, HostedUpdatesDb, Updates}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.BitVector

class HostedUpdatesDbSpec extends AnyFunSuite {
  def sig: ByteVector64 = Crypto.sign(randomBytes32, randomKey)
  val a: Crypto.PrivateKey = randomKey
  val b: Crypto.PrivateKey = generatePubkeyHigherThan(a)
  val c: Crypto.PrivateKey = randomKey
  val d: Crypto.PrivateKey = generatePubkeyHigherThan(c)

  private def generatePubkeyHigherThan(priv: PrivateKey) = {
    var res = priv
    while (!Announcements.isNode1(priv.publicKey, res.publicKey)) res = randomKey
    res
  }

  test("Add announce and related updates") {
    HCTestUtils.resetEntireDatabase()

    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)

    val insertQuery = Updates.insert(channel.shortChannelId.toLong, channelAnnouncementCodec.encode(channel).require.toHex)
    Blocking.txWrite(insertQuery, Config.db) // Insert
    Blocking.txWrite(insertQuery, Config.db) // Update on conflict

    assert(channelAnnouncementCodec.decode(BitVector.fromValidHex(Blocking.txRead(Updates.findNotStaleCompiled(0L).result, Config.db).head._3)).require.value === channel)

    val channel1 = channel.copy(chainHash = Block.LivenetGenesisBlock.hash)
    val updateQuery = Updates.insert(channel.shortChannelId.toLong, channelAnnouncementCodec.encode(channel1).require.toHex)
    Blocking.txWrite(updateQuery, Config.db) // Update on conflict, also announce data has changed
    Blocking.txWrite(updateQuery, Config.db) // Update on conflict

    val res1 = Blocking.txRead(Updates.findNotStaleCompiled(0L).result, Config.db).head
    assert(channelAnnouncementCodec.decode(BitVector.fromValidHex(res1._3)).require.value === channel1)
    assert(res1._4.isEmpty)

    Blocking.txWrite(Updates.update1st(channel_update_1.shortChannelId.toLong, channelUpdateCodec.encode(channel_update_1).require.toHex, channel_update_1.timestamp), Config.db)
    Blocking.txWrite(Updates.update2nd(channel_update_2.shortChannelId.toLong, channelUpdateCodec.encode(channel_update_2).require.toHex, channel_update_1.timestamp), Config.db)

    val res2 = Blocking.txRead(Updates.findNotStaleCompiled(0L).result, Config.db).head
    assert(channelUpdateCodec.decode(BitVector.fromValidHex(res2._4.get)).require.value === channel_update_1)
    assert(channelUpdateCodec.decode(BitVector.fromValidHex(res2._5.get)).require.value === channel_update_2)

    Blocking.txWrite(Updates.findAnnounceOldUpdatableCompiled(System.currentTimeMillis + 1000).delete, Config.db)
    assert(Blocking.txRead(Updates.findNotStaleCompiled(0L).result, Config.db).isEmpty)
  }

  test("Use HostedUpdatesDb") {
    HCTestUtils.resetEntireDatabase()
    val udb = new HostedUpdatesDb(Config.db)

    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, Tools.invalidPubKey, Tools.invalidPubKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel_2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(43), c.publicKey, d.publicKey, Tools.invalidPubKey, Tools.invalidPubKey, sig, sig, ByteVector64.Zeroes, ByteVector64.Zeroes)

    val channel_update_1_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)
    val channel_update_1_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)

    val channel_update_2_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, c, d.publicKey, ShortChannelId(43), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, true)
    assert(udb.getPHCMap(System.currentTimeMillis).isEmpty)

    Blocking.txWrite(udb.addAnnounce(channel_1), Config.db)
    Blocking.txWrite(DBIO.seq(udb.addUpdate(channel_update_2_1), udb.addUpdate(channel_update_1_1)), Config.db)
    val map1 = udb.getPHCMap(System.currentTimeMillis)(channel_1.shortChannelId)
    assert(map1.channelUpdate1.size === 1)
    assert(map1.channelUpdate1.get === channel_update_1_1)
    assert(map1.channelUpdate2.isEmpty)

    Blocking.txWrite(DBIO.seq(udb.addUpdate(channel_update_1_2), udb.addUpdate(channel_update_1_1)), Config.db)
    val map2 = udb.getPHCMap(System.currentTimeMillis)(channel_1.shortChannelId)
    assert(map2.channelUpdate1.get === channel_update_1_1)
    assert(map2.channelUpdate2.get === channel_update_1_2)

    Blocking.txWrite(DBIO.seq(udb.addAnnounce(channel_2), udb.addUpdate(channel_update_2_1), udb.addUpdate(channel_update_2_1)), Config.db)
    val map3 = udb.getPHCMap(System.currentTimeMillis + 12.days.toSeconds)
    assert(map3(channel_1.shortChannelId).channelUpdate1.get === channel_update_1_1)
    assert(map3(channel_1.shortChannelId).channelUpdate2.get === channel_update_1_2)
    assert(map3(channel_2.shortChannelId).channelUpdate1.get === channel_update_2_1)
    assert(map3(channel_2.shortChannelId).channelUpdate2.isEmpty)

    assert(udb.getPHCMap(System.currentTimeMillis + 15.days.toSeconds).isEmpty)

    udb.pruneOldUpdates1(System.currentTimeMillis + 15.days.toSeconds)
    val map4 = udb.getPHCMap(System.currentTimeMillis + 12.days.toSeconds)(channel_1.shortChannelId)
    assert(map4.channelUpdate1.isEmpty)
    assert(map4.channelUpdate2.get === channel_update_1_2)

    udb.pruneOldUpdates2(System.currentTimeMillis + 15.days.toSeconds)
    val map5 = udb.getPHCMap(System.currentTimeMillis + 12.days.toSeconds)(channel_1.shortChannelId)
    assert(map5.channelUpdate1.isEmpty)
    assert(map5.channelUpdate2.isEmpty)

    udb.pruneOldAnnounces(System.currentTimeMillis + 30.days.toSeconds)
    assert(udb.getPHCMap(System.currentTimeMillis + 12.days.toSeconds).isEmpty)
    assert(Blocking.txRead(Updates.findNotStaleCompiled(0L).result, Config.db).isEmpty)
  }
}
