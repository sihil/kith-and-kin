package controllers

import java.util.UUID

import com.gu.googleauth.{Actions, GoogleAuthConfig, UserIdentity}
import db.{EmailRepository, InviteRepository, PaymentRepository}
import helpers.{AWSEmail, EmailService, RsvpCookie, RsvpId, Secret}
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.ws.WSClient
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._
import views.html.helper.options

import scala.concurrent.Future
import scala.language.{postfixOps, reflectiveCalls}

object HttpResults extends Results

object Whitelist {
  def Emails(emails: String*): Set[String] = {
    val emailSet: Set[String] = emails.toSet
    emailSet.flatMap { email =>
      if (email.toLowerCase.endsWith("gmail.com"))
        Set(email, email.stripSuffix("gmail.com") + "googlemail.com")
      else
        Set(email)
    }
  }
  val superusers = Emails(
    "simon@hildrew.net",
    "c.l.kelling@gmail.com",
    "fionakelling@gmail.com",
    "matray@gmail.com"
  )
  val kidsUsers = Emails(
    "buckle482@gmail.com",
    "calamb1984@gmail.com"
  )
  val otherUsers = Emails(
    "pranita88@gmail.com",
    "katyharris@hotmail.co.uk",
    "katyharris182@gmail.com"
  )
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
  override val defaultRedirectTarget = routes.AdminController.summary()
  override val failureRedirectTarget = routes.KithAndKinController.index()

  class WhitelistedActionFilter(whitelists: Set[String]*) extends ActionFilter[({ type R[A] = AuthenticatedRequest[A, UserIdentity] })#R] {
    override protected def filter[A](request: AuthenticatedRequest[A, UserIdentity]): Future[Option[Result]] = {
      val whitelist = (Whitelist.superusers +: whitelists).reduce(_ ++ _)
      Future.successful{
        if (!whitelist.contains(request.user.email)) {
          Some(HttpResults.Unauthorized(s"User with e-mail ${request.user.email} is not authorized to view this page"))
        } else None
      }
    }
  }

  def WhitelistAction(whitelists: Set[String]*) = new WhitelistedActionFilter(whitelists:_*) compose AuthAction
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
                      paymentRepository: PaymentRepository, emailService: EmailService,
                      emailRepository: EmailRepository, emailTemplates: EmailTemplates)
  extends Controller with AuthActions {

  def summary = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
    def comingSummary(questions: List[Questions]): InviteSummary = {
      // find all invites that are marked as coming
      val yes = questions.filter(_.coming.nonEmpty)
      InviteSummary(yes, yes.map(_.numberAdultsComing).sum, yes.map(_.numberChildrenComing).sum)
    }
    def notComingSummary(questions: List[Questions]): InviteSummary = {
      val no = questions.filter(_.notComing.nonEmpty)
      InviteSummary(no, no.map(_.adultsNotComing.size).sum, no.map(_.childrenNotComing.size).sum)
    }
    val invites = inviteRepository.getInviteList.toList
    val questionList = invites.map(i => QuestionMaster.questions(i, _.rsvp))

    val overall = InviteSummary(questionList)
    val coming = comingSummary(questionList)
    val notComing = notComingSummary(questionList)
    val yetToRsvp = InviteSummary(questionList.filter(_.maybeRsvpFacet.isEmpty))

    Ok(views.html.admin.summary(overall, coming, notComing, yetToRsvp))
  }

  def list = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
    val invites = inviteRepository.getInviteList.toList
    Ok(views.html.admin.inviteList(invites))
  }

  def payments = WhitelistAction() { implicit r =>
    val invitesMap = inviteRepository.getInviteList.toList.map(i => i.id -> i).toMap
    val questionsMap = invitesMap.mapValues { invite => QuestionMaster.questions(invite, _.rsvp) }
    val payments = paymentRepository.getPaymentList.toList
    val total = payments.map(_.amount).sum
    val confirmed = payments.filter(_.confirmed).map(_.amount).sum
    val paymentList = payments.flatMap { payment => invitesMap.get(payment.inviteId).map(invite => (payment, invite)) }
    val inviteStatusList = questionsMap.filter(_._2.maybeRsvpFacet.nonEmpty).map { case (id, questions) =>
      val totalForInvite = questions.totalPrice
      val paymentsForInvite = payments.filter(_.inviteId == id)
      val paidForInvite = paymentsForInvite.map(_.amount).sum
      val confirmedForInvite = paymentsForInvite.filter(_.confirmed).map(_.amount).sum
      InvitePaymentStatus(questions.invite, totalForInvite, paidForInvite, confirmedForInvite)
    }
    val owed = inviteStatusList.map(_.total).sum
    val outstandingInvitesStatusList = inviteStatusList.filter { status => status.total != status.paid || status.total != status.confirmed }
    val allBreakdowns = questionsMap.values.flatMap(_.breakdown).flatMap(_.toList)
    val breakdownSummary = allBreakdowns.groupBy(_.desc).mapValues { breakdowns =>
      breakdowns.reduce { (one, two) => one.copy(subTotal = one.subTotal + two.subTotal) }
    }.values.toList.sortBy(_.desc)
    Ok(views.html.admin.paymentSummary(owed, total, confirmed, paymentList, outstandingInvitesStatusList.toList, breakdownSummary))
  }

  def accommodation = WhitelistAction() { implicit r =>
    val invites = inviteRepository.getInviteList.toList
    val questionsList = invites.map(i => QuestionMaster.questions(i, _.rsvp)).filter(_.coming.nonEmpty)
    def find[A](accomType: String)(include: Rsvp => A): List[(Questions, A)] = {
      questionsList.filter(_.rsvpFacet.accommodation.contains(accomType)).map { questions => questions -> include(questions.rsvpFacet) }
    }
    val ownTent = find(Accommodation.OWN_TENT)(_ => ()).map(_._1)
    val camper = find(Accommodation.CAMPER)(_.hookup.get)
    val caravan = find(Accommodation.CARAVAN)(_.hookup.get)
    val bellTent = find(Accommodation.BELL_TENT) { rsvp => (rsvp.bellTentSharing, rsvp.bellTentBedding) }
    val offSite = find(Accommodation.OFF_SITE)(_.offSiteLocation.get)
    Ok(views.html.admin.accommodation(ownTent, camper, caravan, bellTent, offSite))
  }

  def getInvolved = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
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

  def foodDash(noLinks: Boolean) = WhitelistAction(Whitelist.kidsUsers) { implicit r =>
    val invites = inviteRepository.getInviteList.toList
    val questionsList = invites.map(i => QuestionMaster.questions(i, _.rsvp)).filter(_.coming.nonEmpty)
    def count(p: Rsvp => Boolean): (List[Adult], List[Child]) = {
      val toCount = questionsList.filter(q => p(q.rsvpFacet))
      val adults = toCount.flatMap(_.adultsComing)
      val children = toCount.flatMap(_.childrenComing)
      adults -> children
    }
    val arrival = Seq("thursEve", "thursLate", "friMorn", "friLunch", "friAft", "friEve", "friLate")
    val meals = Seq(
      "Thursday night" -> count(_.arrival.exists(arrival.take(1).contains)),
      "Friday breakfast" -> count { rsvp =>
        rsvp.breakfast && rsvp.arrival.exists(arrival.take(2).contains)
      },
      "Friday lunch" -> count(_.arrival.exists(arrival.take(4).contains)),
      "Friday dinner" -> count(_.arrival.exists(arrival.take(6).contains)),
      "Saturday breakfast" -> count(_.breakfast),
      "Saturday wedding feast" -> count(_ => true),
      "Saturday evening" -> count(_ => true),
      "Sunday breakfast" -> count(_.breakfast),
      "Sunday lunch" -> count(_.departure.exists(Set("sunLunch", "sunAft").contains))
    )
    val diets = questionsList.sortBy(_.invite.giveMeFirstNames).flatMap{ questions =>
      questions.rsvpFacet.dietaryDetails.map { diet =>
        val context = (questions.rsvpFacet.arrival, questions.rsvpFacet.departure) match {
          case (Some(arr), Some(dep)) => s"$arr to $dep"
          case _ => "???"
        }
        (questions.invite.id.toString, questions.adultsComing ++ questions.childrenComing, diet, context)
      }
    }
    Ok(views.html.admin.foodDash(meals, diets, noLinks))
  }

  def contacts(selected: Option[String]) = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
    val options: List[(String, Questions => Boolean)] = List(
      "All" -> { _ => true },
      "Just parents" -> { _.childrenComing.nonEmpty },
      "On site" -> { !_.rsvpFacet.accommodation.contains(Accommodation.OFF_SITE)}
    )
    val selectedOption = selected.getOrElse(options.head._1)
    val maybeFilter = options.find(_._1 == selectedOption).map(_._2)
    maybeFilter match {
      case Some(filter) =>
        val invites = inviteRepository.getInviteList.toList
        val questionsList = invites.map(i => QuestionMaster.questions(i, _.rsvp))
          .filter(_.coming.nonEmpty)
          .filter(filter)
        Ok(views.html.admin.contactDashboard(questionsList, options.map(_._1), selectedOption))
      case _ =>
        NotFound("No such contact filter")
    }
  }

  def arrivalAndDepartures(sortByDep: Boolean) = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
    val arrivalSlots = List("thursEve", "thursLate", "friMorn", "friLunch", "friAft", "friEve", "friLate")
    val departureSlots = List("sunMorn", "sunLunch", "sunAft")

    implicit class RichList[A](list: List[A]) {
      def takeWhileInc(p: A => Boolean) = {
        val (start, end) = list.span(p)
        start :+ end.head
      }
    }

    val invites = inviteRepository.getInviteList.toList
    val questionsList = invites.map(i => QuestionMaster.questions(i, _.rsvp)).filter(_.coming.nonEmpty)

    val arrDeps = questionsList.flatMap { questions =>
      for {
        arr <- questions.rsvpFacet.arrival
        dep <- questions.rsvpFacet.departure
      } yield {
        (questions.invite, arrivalSlots.dropWhile(arr!=), departureSlots.takeWhileInc(dep!=))
      }
    }.sortBy{ case (_, arr, dep) =>
      val arrKey = arrivalSlots.indexOf(arr.head)
      val depKey = departureSlots.indexOf(dep.last)
      if (sortByDep) (-depKey, arrKey) else (arrKey, -depKey)
    }
    Ok(views.html.admin.arrivalAndDeparture(arrDeps, sortByDep))
  }

  def details(inviteId: String) = WhitelistAction(Whitelist.kidsUsers ++ Whitelist.otherUsers) { implicit r =>
    val maybeInvite = inviteRepository.getInvite(UUID.fromString(inviteId))
    maybeInvite.map { invite =>
      val questions = QuestionMaster.questions(invite, _.rsvp)
      val draftQuestions = QuestionMaster.questions(invite, _.draftRsvp)
      Ok(views.html.admin.details(questions, draftQuestions))
    }.getOrElse(NotFound)
  }

  def create = WhitelistAction() {
    val adult = Adult("Simon Hildrew")
    val invite = Invite(UUID.randomUUID(), 0, None, Some("simon@hildrew.net"), emailPreferred = false, Some("62 Allendale Close\nLondon\nSE5 8SG"), 0, None, List(adult), Nil, "just me", None)
    inviteRepository.putInvite(invite)
    Ok("done")
  }

  def emailDashboard = WhitelistAction() { implicit request =>
    Ok(views.html.admin.emailDashboard(
      emailTemplates.allTemplates,
      emailRepository.getEmailList.toSeq,
      inviteRepository.getInviteList.toSeq
    ))
  }

  def previewEmail(templateName: String) = WhitelistAction() { implicit request =>
    val maybeTemplate = emailTemplates.allTemplates.find(_.name == templateName)
    maybeTemplate.map { template =>
      val invites = inviteRepository.getInviteList.toSeq
      if (invites.forall(template.preSendCheck)) {
        val emailsToSend = AWSEmail.fromTemplate(template, invites)
        Ok(views.html.admin.emailPreviews(template, emailsToSend))
      } else {
        InternalServerError(s"The pre-send checks for email template '${template.name}' did not pass")
      }
    }.getOrElse(NotFound(s"No template called $templateName"))
  }

  def sendEmail(templateName: String) = WhitelistAction() { implicit request =>
    val maybeTemplate = emailTemplates.allTemplates.find(_.name == templateName)
    maybeTemplate.map { template =>
      val emailRecord = Email(
        id = UUID.randomUUID(),
        template = template.name,
        sentDate = new DateTime()
      )

      val invites = inviteRepository.getInviteList.toSeq

      if (invites.forall(template.preSendCheck)) {
        emailRepository.putEmail(emailRecord) match {
          case Left(_) =>
            InternalServerError("Problem recording email in database")
          case Right(_) =>
            val emailsToSend = AWSEmail.fromTemplate(template, invites)
            val results = emailsToSend.flatMap { case (invite, email) =>
              val result = emailService.sendEmail(email).map(email.to ->)
              template.postSendUpdate.flatMap(_(invite)).foreach(inviteRepository.putInvite)
              result
            }
            emailRepository.putEmail(emailRecord.copy(sentTo = results.map(_._1).toList))
            Redirect(routes.AdminController.emailDashboard())
        }
      } else {
        InternalServerError(s"The pre-send checks for email template '${template.name}' did not pass")
      }
    }.getOrElse(NotFound(s"No template called $templateName"))
  }

  //noinspection MutatorLikeMethodIsParameterless
  def setAllSecrets = WhitelistAction() { request =>
    inviteRepository.getInviteList.foreach { invite =>
      if (invite.secret.isEmpty) {
        inviteRepository.putInvite(invite.copy(secret = Some(Secret.newSecret())))
      }
    }
    Ok("All secrets set!")
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }

  def uploadCsv = WhitelistAction()(parse.multipartFormData) { r =>
    import kantan.csv.generic._
    import kantan.csv.ops._

    val currentInviteList = inviteRepository.getInviteList
    val existingEmails = currentInviteList.map(_.email).toSet
    val existingName = currentInviteList.map(_.adults.head.name).toSet

    r.body.file("csv").map { csv =>
      Logger.logger.info(s"processing file ${csv.ref.file}")
      val reader = csv.ref.file.asCsvReader[Csv](',', header = true)
      val list = reader.toList.flatMap(_.toList).flatMap(_.toInvite)
      val invitesToInsert = list.filterNot { invite =>
        existingEmails.contains(invite.email) || existingName.contains(invite.adults.head.name)
      }
      invitesToInsert.foreach { invite =>
        inviteRepository.putInvite(invite)
      }
      Ok(s"Inserted ${invitesToInsert.size} invites from CSV (${list.size - invitesToInsert.size} filtered out)")
    }.getOrElse(UnprocessableEntity("No file"))
  }

  def action = WhitelistAction() { r =>
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
        }.getOrElse(NotFound(s"Invite $id not found when toggling invite sent"))
      case Some((id, "toggleEditable")) =>
        inviteRepository.getInvite(id).map { invite =>
          inviteRepository.putInvite(invite.copy(editable = Some(!invite.isEditable)))
          Redirect(routes.AdminController.details(id.toString))
        }.getOrElse(NotFound(s"Invite $id not found when toggling editable"))
      case unknown =>
        NotFound(s"Unknown id/action: $unknown")
    }
  }

  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

}
