package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.InviteRepository
import models.{Adult, Csv, Invite}
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.Future
import scala.io.Source

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

  def index = WhitelistAction { implicit r =>
    val invites = inviteRepository.getInviteList
    Ok(views.html.admin.adminHome(invites.toList))
  }

  def create = WhitelistAction { r =>
    val adult = Adult("Simon Hildrew")
    val invite = Invite(UUID.randomUUID(), None, "simon@hildrew.net", false, Some("62 Allendale Close\nLondon\nSE5 8SG"), 0, List(adult), Nil, "just me", None)
    inviteRepository.putInvite(invite)
    Ok("done")
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }

  def uploadCsv = WhitelistAction(parse.multipartFormData) { r =>
    import kantan.csv.ops._
    import kantan.csv.generic._

    r.body.file("csv").map { csv =>
      Logger.logger.info(s"processing file ${csv.ref.file}")
      Logger.logger.info(Source.fromFile(csv.ref.file).getLines().take(3).mkString("\n"))
      val reader = csv.ref.file.asCsvReader[Csv](',', true)
      val list = reader.toList.flatMap(_.toList).flatMap(_.toInvite)
      list.foreach{ invite =>
        inviteRepository.putInvite(invite)
        // this is so that we don't breach the limit
        //Thread.sleep(200)
      }
      Ok(s"Inserted ${list.size} invites from CSV")
    }.getOrElse(UnprocessableEntity("No file"))
  }

  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

}
