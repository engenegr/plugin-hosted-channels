package fr.acinq.hc.app

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, NodeParams, TestConstants}
import slick.jdbc.PostgresProfile

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await


object HCTestUtils {
  def resetEntireDatabase(db: PostgresProfile.backend.Database): Unit = {
    val setup = DBIO.seq(
      fr.acinq.hc.app.db.Channels.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Updates.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Channels.model.schema.create,
      fr.acinq.hc.app.db.Updates.model.schema.create,
      fr.acinq.hc.app.db.Preimages.model.schema.dropIfExists,
      fr.acinq.hc.app.db.Preimages.model.schema.create
    )
    Await.result(db.run(setup.transactionally), 10.seconds)
  }

  def testKit(nodeParams: NodeParams)(implicit system: ActorSystem): (Kit, TestProbe) = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val testPaymentInitiator = TestProbe()
    val server = TestProbe()
    val kit = Kit(
      nodeParams,
      system,
      watcher.ref,
      paymentHandler.ref,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      testPaymentInitiator.ref,
      server.ref,
      new TestWallet())
    (kit, relayer)
  }
}
