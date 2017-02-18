package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.{InviteRepository, PaymentRepository}
import helpers.{ListCache, RsvpCookie, RsvpId}
import models._
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.Future
import scala.language.{postfixOps, reflectiveCalls}

import cats.syntax.apply._

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

case class InviteSummary(invites: List[Invite],
                         adultCount: Int,
                         childCount: Int)
object InviteSummary {
  def apply(invites: List[Invite]): InviteSummary = {
    InviteSummary(
      invites,
      invites.map(_.adults.size).sum,
      invites.map(_.children.size).sum
    )
  }
}

case class InvitePaymentStatus(invite: Invite, total: Int, paid: Int, confirmed: Int)

class AdminController(val wsClient: WSClient, val baseUrl: String, inviteRepository: InviteRepository,
                      paymentRepository: PaymentRepository, listCache: ListCache)
  extends Controller with AuthActions {

  def clear() = WhitelistAction {
    listCache.clear()
    Ok("Cleared")
  }

  def index = WhitelistAction { implicit r =>
    def comingSummary(invites: List[Invite]): InviteSummary = {
      // find all invites that are marked as coming
      val yes = invites.filter(_.rsvp.flatMap(_.coming).getOrElse(false))
      InviteSummary(yes, yes.map(_.adultsComing.size).sum, yes.map(_.childrenComing.size).sum)
    }
    def notComingSummary(invites: List[Invite]): InviteSummary = {
      val no = invites.filter(i => i.adultsNotComing.nonEmpty || i.childrenNotComing.nonEmpty)
      InviteSummary(no, no.map(_.adultsNotComing.size).sum, no.map(_.adultsNotComing.size).sum)
    }
    val invites = listCache.getInvites
    val overall = InviteSummary(invites)
    val coming = comingSummary(invites)
    val notComing = notComingSummary(invites)

    Ok(views.html.admin.summary(overall, coming, notComing))
  }

  def list = WhitelistAction { implicit r =>
    val invites = listCache.getInvites
    Ok(views.html.admin.inviteList(invites))
  }

  def payments = WhitelistAction { implicit r =>
    val invites = listCache.getInvites.map(i => i.id -> i).toMap
    val payments = listCache.getPayments
    val total = payments.map(_.amount).sum
    val confirmed = payments.filter(_.confirmed).map(_.amount).sum
    val paymentList = payments.flatMap { payment => invites.get(payment.inviteId).map(invite => (payment, invite)) }
    val inviteStatusList = invites.filter(_._2.rsvp.nonEmpty).map { case (id, invite) =>
      val questions = QuestionMaster.questions(invite)
      val totalForInvite = questions.finalResponse.totalPrice
      val paymentsForInvite = payments.filter(_.inviteId == id)
      val paidForInvite = paymentsForInvite.map(_.amount).sum
      val confirmedForInvite = paymentsForInvite.filter(_.confirmed).map(_.amount).sum
      InvitePaymentStatus(invite, totalForInvite, paidForInvite, confirmedForInvite)
    }
    val owed = inviteStatusList.map(_.total).sum
    val outstandingInvitesStatusList = inviteStatusList.filter{status => status.total != status.paid || status.total != status.confirmed}
    Ok(views.html.admin.paymentSummary(owed, total, confirmed, paymentList, outstandingInvitesStatusList.toList))
  }

  def accommodation = WhitelistAction { implicit r =>
    val invites = listCache.getInvites
    val inviteRsvps = invites.flatMap(i => i.rsvp.map(r => i -> r)).groupBy(_._2.accommodation)
    def find[A](accomType: String)(include: Rsvp => A): List[(Invite, A)] = {
      invites.filter(_.rsvp.flatMap(_.accommodation).contains(accomType)).map{ invite => invite -> include(invite.rsvp.get) }
    }
    val ownTent = find(Accommodation.OWN_TENT)(_ => ()).map(_._1)
    val camper = find(Accommodation.CAMPER)(_.hookup.get)
    val caravan = find(Accommodation.CARAVAN)(_.hookup.get)
    val bellTent = find(Accommodation.BELL_TENT)(rsvp => (rsvp.bellTentSharing.get, rsvp.bellTentBedding.get))
    val offSite = find(Accommodation.OFF_SITE)(_.offSiteLocation.get)
    Ok(views.html.admin.accommodation(ownTent, camper, caravan, bellTent, offSite))
  }

  def create = WhitelistAction {
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
          Redirect(routes.AdminController.list())
        }.getOrElse(NotFound(s"Invite $id not found"))
      case unknown =>
        NotFound(s"Unknown id/action: $unknown")
    }
  }

  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

}
