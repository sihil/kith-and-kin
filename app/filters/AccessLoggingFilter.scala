package filters

import akka.stream.Materializer
import helpers.RsvpCookie
import play.api.Logger
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AccessLoggingFilter()(implicit val mat: Materializer) extends Filter {

  val accessLogger = Logger("play.access")

  def apply(next: (RequestHeader) => Future[Result])(request: RequestHeader): Future[Result] = {
    val resultFuture = next(request)

    if (!request.uri.startsWith("/assets/")) {
      val rsvpId = RsvpCookie.parse(request.cookies).map(id => s""" rsvpId=${id.id}""").getOrElse("")
      resultFuture.foreach(result => {
        val ua = request.headers.get(HttpConstants.USER_AGENT).map(header => s""" ua="$header"""").getOrElse("")
        val msg = s"method=${request.method} uri=${request.uri} remote-address=${request.remoteAddress}" +
          s" status=${result.header.status}$rsvpId$ua"
        accessLogger.info(msg)
      })
    }

    resultFuture
  }
}