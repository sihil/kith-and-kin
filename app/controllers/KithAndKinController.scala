package controllers

import play.api.mvc.{Action, Controller}

class KithAndKinController extends Controller {
  def healthcheck = Action {
    Ok("OK")
  }

  def index = Action {
    Ok(views.html.index())
  }

  def accommodation = Action {
    Ok(views.html.accommodation())
  }

  def getInvolved = Action {
    Ok(views.html.getInvolved())
  }
}
