package controllers

import org.joda.time.{DateTime, Interval, Period}
import play.api.mvc.{Action, Controller}

class KithAndKinController extends Controller {
  val firstDay = new DateTime(2017, 8, 4, 0, 0)
  val fifteenMinuteCache = CACHE_CONTROL -> "public; max-age=900"

  def healthcheck = Action {
    Ok("OK")
  }

  def index = Action {
    Ok(views.html.index()).withHeaders(fifteenMinuteCache)
  }

  def accommodation = Action {
    Ok(views.html.accommodation()).withHeaders(fifteenMinuteCache)
  }

  def getInvolved = Action {
    Ok(views.html.getInvolved()).withHeaders(fifteenMinuteCache)
  }

  def festivalInfo = Action {
    Ok(views.html.festivalInfo(0)).withHeaders(fifteenMinuteCache)
  }

  def robots = Action {
    Ok(
      """
        |User-agent: *
        |Disallow: /
      """.stripMargin)
  }
}
