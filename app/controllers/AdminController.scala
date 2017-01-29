package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.InviteRepository
import models.{Adult, Invite}
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.Future

object HttpResults extends Results

trait AuthActions extends Actions {
  val whitelist = Set("simon@hildrew.net", "c.l.kelling@gmail.com", "c.l.kelling@googlemail.com")

  def baseUrl: String
  def secure = baseUrl.startsWith("https")
  def host = baseUrl.stripSuffix("/").split("://").last

  // Google configuration
  override val authConfig = GoogleAuthConfig.withNoDomainRestriction(
    clientId     = "213803222033-iqffj49kuvpu5qvhmhpq59i9p4ek0as6.apps.googleusercontent.com",
    clientSecret = "5RrITIxJdzeyaR4hf2_AcBdW",
    redirectUrl  = routes.AdminController.oauth2Callback().absoluteURL(secure, host)
  )
  // your app's routing
  override val loginTarget = routes.AdminController.loginAction()
  override val defaultRedirectTarget = routes.AdminController.index()
  override val failureRedirectTarget = routes.KithAndKinController.index()

  object WhitelistedActionFilter extends ActionFilter[({ type R[A] = AuthenticatedRequest[A, UserIdentity] })#R] {
    override protected def filter[A](request: AuthenticatedRequest[A, UserIdentity]): Future[Option[Result]] = {
      Future.successful{
        if (!whitelist.contains(request.user.email)) {
          Some(HttpResults.Unauthorized(s"User with e-mail ${request.user.email} is not authorized"))
        } else None
      }
    }
  }

  val WhitelistAction = WhitelistedActionFilter compose AuthAction
}

class AdminController(val wsClient: WSClient, val baseUrl: String, inviteRepository: InviteRepository)
  extends Controller with AuthActions {

  def index = WhitelistAction { r =>
    val invites = inviteRepository.getInviteList
    Ok(views.html.admin.adminHome(invites.toList))
  }

  def create = WhitelistAction { r =>
    val adult = Adult("Simon Hildrew")
    val invite = Invite(UUID.randomUUID(), None, "simon@hildrew.net", "07968146282", "62 Allendale Close\nLondon\nSE5 8SG", 0, List(adult), Nil)
    inviteRepository.putInvite(invite)
    Ok("done")
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }



  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

}
