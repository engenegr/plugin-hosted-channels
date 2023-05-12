package fr.acinq.hc.app

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.hc.app.Tools.{DuplicateHandler, hostedShortChanId}
import fr.acinq.hc.app.db.{Blocking, HostedUpdatesDb, PreimagesDb, Updates}
import fr.acinq.hc.app.network.{CollectedGossip, PHC}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.BitVector
import slick.jdbc.PostgresProfile.api._


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
    HCTestUtils.resetEntireDatabase(HCTestUtils.config.db)

    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, hostedShortChanId(42), a.publicKey, b.publicKey, a.publicKey, b.publicKey, sig, sig, sig, sig)
    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)

    val insertQuery = Updates.insert(channel.shortChannelId.toLong, channelAnnouncementCodec.encode(channel).require.toHex)
    Blocking.txWrite(insertQuery, HCTestUtils.config.db) // Insert
    Blocking.txWrite(insertQuery, HCTestUtils.config.db) // Update on conflict

    assert(channelAnnouncementCodec.decode(BitVector.fromValidHex(Blocking.txRead(Updates.model.result, HCTestUtils.config.db).head._3)).require.value == channel)

    val channel1 = channel.copy(chainHash = Block.LivenetGenesisBlock.hash)
    val updateQuery = Updates.insert(channel.shortChannelId.toLong, channelAnnouncementCodec.encode(channel1).require.toHex)
    Blocking.txWrite(updateQuery, HCTestUtils.config.db) // Update on conflict, also announce data has changed
    Blocking.txWrite(updateQuery, HCTestUtils.config.db) // Update on conflict

    val res1 = Blocking.txRead(Updates.model.result, HCTestUtils.config.db).head
    assert(channelAnnouncementCodec.decode(BitVector.fromValidHex(res1._3)).require.value == channel1)
    assert(res1._4.isEmpty)

    Blocking.txWrite(Updates.update1st(channel_update_1.shortChannelId.toLong, channelUpdateCodec.encode(channel_update_1).require.toHex, channel_update_1.timestamp.toLong), HCTestUtils.config.db)
    Blocking.txWrite(Updates.update2nd(channel_update_2.shortChannelId.toLong, channelUpdateCodec.encode(channel_update_2).require.toHex, channel_update_1.timestamp.toLong), HCTestUtils.config.db)

    val res2 = Blocking.txRead(Updates.model.result, HCTestUtils.config.db).head
    assert(channelUpdateCodec.decode(BitVector.fromValidHex(res2._4.get)).require.value == channel_update_1)
    assert(channelUpdateCodec.decode(BitVector.fromValidHex(res2._5.get)).require.value == channel_update_2)

    Blocking.txWrite(Updates.findAnnounceDeletableCompiled.delete, HCTestUtils.config.db)
    assert(Blocking.txRead(Updates.model.result, HCTestUtils.config.db).nonEmpty)

    Blocking.txWrite(Updates.findUpdate1stOldUpdatableCompiled(System.currentTimeMillis / 1000 + PHC.staleThreshold + 1).update(None), HCTestUtils.config.db)
    assert(Blocking.txRead(Updates.model.result, HCTestUtils.config.db).nonEmpty)
    Blocking.txWrite(Updates.findUpdate2ndOldUpdatableCompiled(System.currentTimeMillis / 1000 + PHC.staleThreshold + 1).update(None), HCTestUtils.config.db)
    Blocking.txWrite(Updates.findAnnounceDeletableCompiled.delete, HCTestUtils.config.db)
    assert(Blocking.txRead(Updates.model.result, HCTestUtils.config.db).isEmpty)
  }

  test("Use HostedUpdatesDb") {
    HCTestUtils.resetEntireDatabase(HCTestUtils.config.db)
    val udb = new HostedUpdatesDb(HCTestUtils.config.db)



    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, hostedShortChanId(42), a.publicKey, b.publicKey, a.publicKey, b.publicKey, sig, sig, sig, sig)
    val channel_2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, hostedShortChanId(43), c.publicKey, d.publicKey, c.publicKey, d.publicKey, sig, sig, sig, sig)

    val channel_update_1_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    val channel_update_1_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)

    val channel_update_2_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, c, d.publicKey, hostedShortChanId(43), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    assert(udb.getState.channels.isEmpty)

    Blocking.txWrite(udb.addAnnounce(channel_1), HCTestUtils.config.db)
    Blocking.txWrite(DBIO.seq(udb.addUpdate(channel_update_2_1), udb.addUpdate(channel_update_1_1)), HCTestUtils.config.db)
    val map1 = udb.getState.channels(channel_1.shortChannelId)
    assert(map1.channelUpdate1.size == 1)
    assert(map1.channelUpdate1.get == channel_update_1_1)
    assert(map1.channelUpdate2.isEmpty)

    Blocking.txWrite(DBIO.seq(udb.addUpdate(channel_update_1_2), udb.addUpdate(channel_update_1_1)), HCTestUtils.config.db)
    val map2 = udb.getState.channels(channel_1.shortChannelId)
    assert(map2.channelUpdate1.get == channel_update_1_1)
    assert(map2.channelUpdate2.get == channel_update_1_2)

    Blocking.txWrite(DBIO.seq(udb.addAnnounce(channel_2), udb.addAnnounce(channel_2), udb.addUpdate(channel_update_2_1), udb.addUpdate(channel_update_2_1)), HCTestUtils.config.db)
    val map3 = udb.getState.channels
    assert(map3(channel_1.shortChannelId).channelUpdate1.get == channel_update_1_1)
    assert(map3(channel_1.shortChannelId).channelUpdate2.get == channel_update_1_2)
    assert(map3(channel_2.shortChannelId).channelUpdate1.get == channel_update_2_1)
    assert(map3(channel_2.shortChannelId).channelUpdate2.isEmpty)

    udb.pruneOldUpdates1(System.currentTimeMillis / 1000 + PHC.staleThreshold + 1)
    udb.pruneOldUpdates2(System.currentTimeMillis / 1000 + PHC.staleThreshold + 1)
    assert(udb.getState.channels.values.flatMap(u => u.channelUpdate1 ++ u.channelUpdate2).isEmpty)

    udb.pruneUpdateLessAnnounces
    assert(udb.getState.channels.isEmpty)
  }

  test("Collecting gossip") {
    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, hostedShortChanId(42), a.publicKey, b.publicKey, a.publicKey, b.publicKey, sig, sig, sig, sig)
    val channel_update_1_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    val channel_update_1_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, hostedShortChanId(42), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)
    val channel_update_2_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, c, d.publicKey, hostedShortChanId(43), CltvExpiryDelta(5), 7000000.msat, 50000.msat, 100, 500000000L.msat, enable = true)

    val collected0 = CollectedGossip(Map.empty)

    val collected1 = collected0.addAnnounce(channel_1, c.publicKey)
    val collected2 = collected1.addAnnounce(channel_1, d.publicKey)

    val collected3 = collected2.addUpdate(channel_update_1_1, c.publicKey)
    val collected4 = collected3.addUpdate(channel_update_1_1, d.publicKey)

    val collected5 = collected4.addUpdate(channel_update_2_1, c.publicKey)
    val collected6 = collected5.addUpdate(channel_update_1_2, c.publicKey)

    assert(collected6.announces(channel_1.shortChannelId).seenFrom == Set(c.publicKey, d.publicKey))
    assert(collected6.updates1(channel_update_1_1.shortChannelId).seenFrom == Set(c.publicKey, d.publicKey))
    assert(collected6.updates1.size == 2)
    assert(collected6.updates2(channel_update_1_2.shortChannelId).seenFrom == Set(c.publicKey))
  }

  test("Inserting preimages") {
    HCTestUtils.resetEntireDatabase(HCTestUtils.config.db)
    val pdb = new PreimagesDb(HCTestUtils.config.db)
    val preimage = randomBytes32

    val dh = new DuplicateHandler[ByteVector32] {
      def insert(data: ByteVector32): Boolean = {
        val hash = Crypto.sha256(preimage)
        pdb.addPreimage(hash, data)
      }
    }

    assert(dh.execute(preimage).isSuccess)
    assert(dh.execute(preimage).isFailure)

    assert(pdb.findByHash(randomBytes32).isEmpty)
    assert(pdb.findByHash(Crypto.sha256(preimage)).contains(preimage))
  }
}
