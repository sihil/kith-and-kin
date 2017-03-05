package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.{InviteRepository, PaymentRepository}
import helpers.{RsvpCookie, RsvpId}
import models._
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.Future
import scala.language.{postfixOps, reflectiveCalls}

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

case class InviteSummary(questionsList: List[Questions],
                         adultCount: Int,
                         childCount: Int)
object InviteSummary {
  def apply(questionsList: List[Questions]): InviteSummary = {
    InviteSummary(
      questionsList,
      questionsList.map(_.invite.adults.size).sum,
      questionsList.map(_.invite.children.size).sum
    )
  }
}

case class InvitePaymentStatus(invite: Invite, total: Int, paid: Int, confirmed: Int)

class AdminController(val wsClient: WSClient, val baseUrl: String, inviteRepository: InviteRepository,
                      paymentRepository: PaymentRepository)
  extends Controller with AuthActions {

  def index = WhitelistAction { implicit r =>
    def comingSummary(questions: List[Questions]): InviteSummary = {
      // find all invites that are marked as coming
      val yes = questions.filter(_.rsvpFacet.coming.getOrElse(false))
      InviteSummary(yes, yes.map(_.numberAdultsComing).sum, yes.map(_.numberChildrenComing).sum)
    }
    def notComingSummary(questions: List[Questions]): InviteSummary = {
      val no = questions.filter(q => q.adultsNotComing.nonEmpty || q.childrenNotComing.nonEmpty)
      InviteSummary(no, no.map(_.adultsNotComing.size).sum, no.map(_.childrenNotComing.size).sum)
    }
    val invites = inviteRepository.getInviteList.toList
    val questionList = invites.map(i => QuestionMaster.questions(i, _.rsvp))

    val overall = InviteSummary(questionList)
    val coming = comingSummary(questionList)
    val notComing = notComingSummary(questionList)

    Ok(views.html.admin.summary(overall, coming, notComing))
  }

  def list = WhitelistAction { implicit r =>
    val invites = inviteRepository.getInviteList.toList
    Ok(views.html.admin.inviteList(invites))
  }

  def payments = WhitelistAction { implicit r =>
    val invites = inviteRepository.getInviteList.toList.map(i => i.id -> i).toMap
    val payments = paymentRepository.getPaymentList.toList
    val total = payments.map(_.amount).sum
    val confirmed = payments.filter(_.confirmed).map(_.amount).sum
    val paymentList = payments.flatMap { payment => invites.get(payment.inviteId).map(invite => (payment, invite)) }
    val inviteStatusList = invites.filter(_._2.rsvp.nonEmpty).map { case (id, invite) =>
      val questions = QuestionMaster.questions(invite, _.rsvp)
      val totalForInvite = questions.totalPrice
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
    val invites = inviteRepository.getInviteList.toList
    val questionsList = invites.map(i => QuestionMaster.questions(i, _.rsvp)).filter(_.coming.nonEmpty)
    def find[A](accomType: String)(include: Rsvp => A): List[(Questions, A)] = {
      questionsList.filter(_.rsvpFacet.accommodation.contains(accomType)).map{ questions => questions -> include(questions.rsvpFacet) }
    }
    val ownTent = find(Accommodation.OWN_TENT)(_ => ()).map(_._1)
    val camper = find(Accommodation.CAMPER)(_.hookup.get)
    val caravan = find(Accommodation.CARAVAN)(_.hookup.get)
    val bellTent = find(Accommodation.BELL_TENT)(rsvp => (rsvp.bellTentSharing.get, rsvp.bellTentBedding.get))
    val offSite = find(Accommodation.OFF_SITE)(_.offSiteLocation.get)
    Ok(views.html.admin.accommodation(ownTent, camper, caravan, bellTent, offSite))
  }

  def getInvolved = WhitelistAction { implicit r =>
    val invites = inviteRepository.getInviteList.toList
    val choices: Seq[(Invite, GetInvolvedChoice, String)] =
      for {
        invite <- invites
        rsvp <- invite.rsvp
        getInvolvedChoiceStr <- rsvp.getInvolvedPreference
        getInvolvedChoice <- GetInvolvedChoice.values.find(_.key == getInvolvedChoiceStr)
        getInvolved <- rsvp.getInvolved
      } yield (invite, getInvolvedChoice, getInvolved)
    val sortedChoices = choices.sortBy(choice => GetInvolvedChoice.values.indexOf(choice._2))
    Ok(views.html.admin.getInvolved(sortedChoices))
  }

  def details(inviteId: String) = WhitelistAction { implicit r =>
    val maybeInvite = inviteRepository.getInvite(UUID.fromString(inviteId))
    maybeInvite.map { invite =>
      val questions = QuestionMaster.questions(invite, _.rsvp)
      val draftQuestions = QuestionMaster.questions(invite, _.draftRsvp)
      Ok(views.html.admin.details(questions, draftQuestions))
    }.getOrElse(NotFound)
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
