package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.InviteRepository
import helpers.{RsvpCookie, RsvpId}
import models.{Adult, Csv, Invite}
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.Future
import scala.io.Source
import scala.language.reflectiveCalls

object HttpResults extends Results

object Whitelist {
  val users = Set("simon@hildrew.net", "c.l.kelling@gmail.com", "c.l.kelling@googlemail.com")
}

trait AuthActions extends Actions {
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
        if (!Whitelist.users.contains(request.user.email)) {
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
    val invite = Invite(UUID.randomUUID(), 0, None, Some("simon@hildrew.net"), emailPreferred = false, Some("62 Allendale Close\nLondon\nSE5 8SG"), 0, None, List(adult), Nil, "just me", None)
    inviteRepository.putInvite(invite)
    Ok("done")
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }

  def uploadCsv = WhitelistAction(parse.multipartFormData) { r =>
    import kantan.csv.ops._
    import kantan.csv.generic._

    val currentInviteList = inviteRepository.getInviteList
    val existingEmails = currentInviteList.map(_.email).toSet
    val existingName = currentInviteList.map(_.adults.head.name).toSet

    r.body.file("csv").map { csv =>
      Logger.logger.info(s"processing file ${csv.ref.file}")
      val reader = csv.ref.file.asCsvReader[Csv](',', header = true)
      val list = reader.toList.flatMap(_.toList).flatMap(_.toInvite)
      val invitesToInsert = list.filterNot{ invite =>
        existingEmails.contains(invite.email) || existingName.contains(invite.adults.head.name)
      }
      invitesToInsert.foreach{ invite =>
        inviteRepository.putInvite(invite)
      }
      Ok(s"Inserted ${invitesToInsert.size} invites from CSV (${list.size - invitesToInsert.size} filtered out)")
    }.getOrElse(UnprocessableEntity("No file"))
  }

  def action = WhitelistAction { r =>
    val action = r.body.asFormUrlEncoded.flatMap { formData =>
      val id = formData.get("id").toList.flatten.headOption.map(UUID.fromString)
      val action = formData.get("action").toList.flatten.headOption
      id.zip(action).headOption
    }
    action match {
      case Some((id, "impersonate")) =>
        val loginCookie = RsvpCookie.make(RsvpId(id, "fakeSecret"), r.secure)
        Redirect(routes.RsvpController.details()).withCookies(loginCookie)
      case Some((id, "toggleInviteSent")) =>
        inviteRepository.getInvite(id).map { invite =>
          inviteRepository.putInvite(invite.copy(sent = !invite.sent))
          Redirect(routes.AdminController.index())
        }.getOrElse(NotFound(s"Invite $id not found"))
      case unknown =>
        NotFound(s"Unknown id/action: $unknown")
    }
  }

  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

}
