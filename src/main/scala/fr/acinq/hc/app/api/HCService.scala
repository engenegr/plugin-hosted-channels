package fr.acinq.hc.app.api

import akka.http.scaladsl.server._
import akka.actor.{ActorRef, ActorSystem}
import fr.acinq.eclair.api.AbstractService
import fr.acinq.hc.app.Vals
import fr.acinq.eclair.Kit
import akka.util.Timeout


class HCService(kit: Kit, worker: ActorRef, vals: Vals) extends AbstractService {

  override val password: String = vals.apiParams.password

  override val actorSystem: ActorSystem = kit.system

  override def route(implicit timeout: Timeout): Route = {
    post {
      path("invokenew") {
        complete(???)
      } ~
      path("externalfulfill") {
        complete(???)
      } ~
      path("hostedchannel") {
        complete(???)
      } ~
      path("overridepropose") {
        complete(???)
      } ~
      path("overrideaccept") {
        complete(???)
      } ~
      path("initrefund") {
        complete(???)
      } ~
      path("finalizerefund") {
        complete(???)
      } ~
      path("makepublic") {
        complete(???)
      } ~
      path("makeprivate") {
        complete(???)
      } ~
      path("verifystate") {
        complete(???)
      }
    }
  }
}
