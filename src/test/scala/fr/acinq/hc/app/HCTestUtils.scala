package fr.acinq.hc.app

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await


object HCTestUtils {
  def resetEntireDatabase(): Unit = {
    val setup = DBIO.seq(
      fr.acinq.hc.app.dbo.Channels.model.schema.dropIfExists,
      fr.acinq.hc.app.dbo.Updates.model.schema.dropIfExists,
      fr.acinq.hc.app.dbo.Channels.model.schema.create,
      fr.acinq.hc.app.dbo.Updates.model.schema.create
    )
    Await.result(Config.db.run(setup.transactionally), 10.seconds)
  }

  def testKit(implicit system: ActorSystem): Kit = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val testPaymentInitiator = TestProbe()
    val server = TestProbe()
    Kit(
      TestConstants.Alice.nodeParams,
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
  }
}
