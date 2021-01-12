package fr.acinq.hc.app.api

import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Cache-Control`}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.http.scaladsl.server.directives.Credentials
import akka.util.Timeout
import fr.acinq.eclair.api.{ErrorResponse, ExtraDirectives}
import grizzled.slf4j.Logging
import scala.concurrent.Future

// important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541

import fr.acinq.eclair.api.JsonSupport.{formats, marshaller, serialization}

trait AbstractService extends ExtraDirectives with Logging {

  def password: String

  implicit val actorSystem: ActorSystem

  // timeout for reading request parameters from the underlying stream
  val paramParsingTimeout: FiniteDuration = 5.seconds

  val apiExceptionHandler: ExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  val apiRejectionHandler: RejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res@HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
      res.withEntity(HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse(ent.data.utf8String))))
  }

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  val timeoutResponse: HttpRequest => HttpResponse = { _ =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("request timed out")))
  }

  // this is the akka timeout
  val timeout: Timeout = Timeout(30.seconds)

  val finalRoute: Route =
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(apiExceptionHandler) {
        handleRejections(apiRejectionHandler) {
          toStrictEntity(paramParsingTimeout) {
            // we ensure that http timeout is greater than akka timeout
            withRequestTimeout(timeout.duration + 2.seconds) {
              withRequestTimeoutResponse(timeoutResponse) {
                authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
                  route(timeout)
                }
              }
            }
          }
        }
      }
    }

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1.second, using = actorSystem.scheduler)(Future.successful(None))(actorSystem.dispatcher) // force a 1 sec pause to deter brute force
  }

  def route(implicit timeout: Timeout): Route
}