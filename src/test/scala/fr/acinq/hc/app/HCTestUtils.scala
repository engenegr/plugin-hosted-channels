package fr.acinq.hc.app

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await


object HCTestUtils {
  def resetEntireDatabase(): Unit = {
    val setup = DBIO.seq(
      fr.acinq.hc.app.dbo.Channels.model.schema.dropIfExists,
      fr.acinq.hc.app.dbo.Channels.model.schema.create
    )
    Await.result(Config.db.run(setup.transactionally), 10.seconds)
  }
}
