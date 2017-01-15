package filters

import akka.stream.Materializer
import controllers.routes
import play.api.http.HeaderNames
import play.api.mvc.Results.Redirect
import play.api.mvc.{Filter, RequestHeader, Result}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object HttpConstants extends HeaderNames

class ForwardingFilter(enabled: Boolean)(implicit val mat: Materializer) extends Filter {
  override def apply(next: (RequestHeader) => Future[Result])(request: RequestHeader): Future[Result] = {
    if (
      enabled
      && request.uri != routes.KithAndKinController.healthcheck().url
      && (!request.headers.get(HttpConstants.X_FORWARDED_PROTO).contains("https")
          || request.domain == "kithandkin.wedding"
        )
    )
    {
      Future successful Redirect(s"https://www.kithandkin.wedding${request.uri}")
    } else {
      next(request).map(_.withHeaders("Strict-Transport-Security" -> "max-age=31536000"))
    }
  }
}
