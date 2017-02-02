package controllers

import play.api.mvc.{Action, Controller}

class KithAndKinController extends Controller {
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
}
