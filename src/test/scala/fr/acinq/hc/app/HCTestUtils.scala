package fr.acinq.hc.app

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.testkit.TestProbe
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.{Kit, NodeParams}
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration._


object HCTestUtils {
  val config = new Config(new File(System getProperty "user.dir"))

  def resetEntireDatabase(db: PostgresProfile.backend.Database): Unit = {
    val setup = DBIO.seq(
      fr.acinq.hc.app.db.Channels.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Updates.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Channels.model.schema.create,
      fr.acinq.hc.app.db.Updates.model.schema.create,
      fr.acinq.hc.app.db.Preimages.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Preimages.model.schema.create
    )
    Await.result(db.run(setup.transactionally), 60.seconds)
  }

  def testKit(nodeParams: NodeParams)(implicit system: ActorSystem): (Kit, TestProbe) = {
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val testPaymentInitiator = TestProbe()
    val channelsListener = TestProbe()
    val server = TestProbe()
    val balance = TestProbe()
    val postman = TestProbe()

    val kit = Kit(nodeParams, system, null, paymentHandler.ref, register.ref,
      relayer.ref, router.ref, switchboard.ref, testPaymentInitiator.ref, server.ref,
      channelsListener.ref.toTyped, balance.ref, postman.ref, new DummyOnChainWallet)
    (kit, relayer)
  }
}
