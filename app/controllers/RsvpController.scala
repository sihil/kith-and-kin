package controllers

import java.util.UUID

import akka.agent.Agent
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.gu.googleauth.{GoogleAuthConfig, UserIdentifier}
import com.softwaremill.quicklens._
import db.InviteRepository
import helpers.{Email, RsvpCookie, RsvpId, Secret}
import models.{Invite, QuestionMaster, Rsvp}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc.{Action, Controller, RequestHeader}

import scala.concurrent.ExecutionContext.Implicits.global

class RsvpController(inviteRepository: InviteRepository, sesClient: AmazonSimpleEmailService) extends Controller with UserIdentifier {

  override def authConfig: GoogleAuthConfig = GoogleAuthConfig.withNoDomainRestriction("","","",enforceValidity = false)

  val lastSeenAgent = Agent[Map[UUID, DateTime]](Map.empty)

  def inviteFrom(request: RequestHeader): Option[Invite] = RsvpCookie.parse(request.cookies).flatMap { rsvpId =>
    val googleAuth = userIdentity(request).filter(user => Whitelist.users.contains(user.email))
    val invite = inviteRepository.getInvite(rsvpId.id)
    (invite, googleAuth) match {
      case (Some(i), None) if RsvpCookie.valid(i, rsvpId) =>
        val now = new DateTime()
        Some(lastSeenAgent().get(i.id) match {
          // seen within last 15 minutes, do nothing
          case Some(lastSeen) if (now.getMillis - lastSeen.getMillis) < 1000 * 900 => i
          // either never seen or more than 10 mins
          case other =>
            val updatedInvite = i.copy(lastLoggedIn = Some(now))
            // update DB and cache
            inviteRepository.putInvite(updatedInvite)
            lastSeenAgent.send(_ + (i.id -> now))
            updatedInvite
        })
      case (Some(i), Some(auth)) =>
        Logger.logger.warn(s"Invite for ${i.email} impersonated by ${auth.email}")
        Some(i)
      case _ => None
    }
  }
  object RsvpLogin extends AuthenticatedBuilder[Invite](inviteFrom, _ => Redirect(routes.RsvpController.start()))

  def start = Action { request =>
    Ok(views.html.rsvp.start(request))
  }

  def lookup = Action { request =>
    val contact = request.body.asFormUrlEncoded.flatMap(_.get("contact").toSeq.flatten.headOption)
    contact match {
      case Some(email) if email.contains("@") =>
        // lookup e-mail in DB
        val list = inviteRepository.getInviteList
        val maybeInvite = list.find(_.email.trim.equalsIgnoreCase(email.trim))
        maybeInvite match {
          case Some(invite) =>
            // generate a new secret
            val secret = Secret.newSecret()
            val withNewSecret = invite.copy(secret = Some(secret))
            inviteRepository.putInvite(withNewSecret)

            // now send e-mail to user with magic URL
            val message =
              s"""
                |Hi ${invite.giveMeAName}!
                |
                |We've found your invite to Kith & Kin - please use the link below to access all areas and continue with your RSVP!
                |
                |You can use this link as many times as you need it, but don't give it to others as anyone who has the link can change your RSVP.
                |
                |${routes.RsvpController.login(invite.id.toString, secret).absoluteURL(request.secure, request.host)}
                |
                |Much love,
                |Simon & Christina
              """.stripMargin
            Email.sendEmail(sesClient, invite.email, "RSVP information", message)

            // now redirect
            Redirect(routes.RsvpController.sentMessage()).flashing("contactType" -> "e-mail address")
          case None =>
            // invite not found - nice error

            Redirect(routes.RsvpController.notRight()).
              flashing(
                "title" -> "Oh no, I can't find you...",
                "message" -> s"I'm sorry, I can't seem to find $email in my list of invites. Double check and try again!"
              )
        }

      case Some(name) =>
        // lookup name in DB
        val list = inviteRepository.getInviteList
        val maybeInvite = list.find{invite => invite.adults.map(_.name).contains(name)}
        maybeInvite match {
          case Some(invite) =>
            // generate a new secret
            val secret = Secret.newSecret()
            val withNewSecret = invite.copy(secret = Some(secret))
            inviteRepository.putInvite(withNewSecret)

            // now send e-mail to user with magic URL
            val message =
              s"""
                |Hi ${invite.giveMeAName}!
                |
                |We've found your invite to Kith & Kin - please use the link below to access all areas and continue with your RSVP!
                |
                |You can use this link as many times as you need it, but don't give it to others as anyone who has the link can change your RSVP.
                |
                |${routes.RsvpController.login(invite.id.toString, secret).absoluteURL(request.secure, request.host)}
                |
                |Much love,
                |Simon & Christina
              """.stripMargin
            Email.sendEmail(sesClient, invite.email, "RSVP information", message)

            // now redirect
            Redirect(routes.RsvpController.sentMessage()).flashing("contactType" -> "e-mail address")
          case None =>
            // invite not found - nice error

            Redirect(routes.RsvpController.notRight()).
              flashing(
                "title" -> "Oh no, I can't find you...",
                "message" -> s"I'm sorry, I can't seem to find '$name' in my list of invites. Double check and try again!"
              )
        }
      case None => Redirect(routes.RsvpController.start())
    }
  }

  def sentMessage = Action { request =>
    val contactType = request.flash.data("contactType")
    Ok(views.html.rsvp.sentMessage(contactType))
  }

  def notRight = Action { request =>
    val title = request.flash.data("title")
    val message = request.flash.data("message")
    Ok(views.html.rsvp.notRight(title, message))
  }

  def login(idString: String, secret: String) = Action { request =>
    val id = UUID.fromString(idString)
    inviteRepository.getInvite(id) match {
      // the ID and secret passed in match one from the DB
      case Some(invite) if invite.secret.contains(secret) =>
        val loginCookie = RsvpCookie.make(RsvpId(id, secret), request.secure)
        Redirect(routes.RsvpController.details()).withCookies(loginCookie)
      // the ID and secret passed in don't match, but you have a valid cookie
      case Some(invite) if RsvpCookie.parse(request.cookies).exists(RsvpCookie.valid(invite, _)) =>
        Redirect(routes.RsvpController.details())
      // no match and no cookie
      case _ =>
        Redirect(routes.RsvpController.notRight()).
          flashing(
            "title" -> "Oh no, your link is no longer valid...",
            "message" -> s"I'm sorry, the link you just used isn't valid. Make sure it is the most recent link you've been sent (requesting a new link makes all old links stop working)."
          )
    }
  }

  def details = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.details(request.user))
  }

  def update(complete: Boolean) = RsvpLogin(parse.json) { request =>
    val questions = QuestionMaster.questions(request.user)
    val updateMap = request.body.as[Map[String, JsValue]]
    val maybeRsvp = if (complete) request.user.rsvp else request.user.draftRsvp
    val updatedRsvp = updateMap.foldLeft(maybeRsvp.getOrElse(Rsvp())) { case (rsvp, (question, answer)) =>
      questions.allQuestions.find(_.key==question).map { q =>
        q.update(rsvp, answer)
      }.getOrElse(rsvp)
    }
    val updatedInvite = if (complete)
      request.user.modify(_.draftRsvp).setTo(Some(updatedRsvp)).modify(_.rsvp).setTo(Some(updatedRsvp))
    else
      request.user.modify(_.draftRsvp).setTo(Some(updatedRsvp))

    val updatedQuestions = QuestionMaster.questions(updatedInvite)

    inviteRepository.putInvite(updatedInvite)

    val updatedQuestionJson = Some(updatedQuestions.questionJson).filterNot(_ == questions.questionJson)
    val updatedPricesJson = Some(updatedQuestions.jsonPrices).filterNot(_ == questions.jsonPrices).map(json => Json.obj("prices" -> json))
    val fields = Seq(Json.obj("result" -> "success")) ++ updatedQuestionJson ++ updatedPricesJson

    Ok(fields.reduce(_ ++ _))
  }

  def notComing() = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.notComing(request.user))
  }

  def accommodation() = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.accommodation(request.user))
  }

  def complete = RsvpLogin { implicit request =>
    val questions = QuestionMaster.questions(request.user)
    Ok(views.html.rsvp.thanks(request.user.rsvp.flatMap(_.coming).get, questions.breakdowns, questions.totalPrice))
  }

  def questions = RsvpLogin { r =>
    val draftAndSentIdentical = r.user.draftRsvp == r.user.rsvp
    val questions = QuestionMaster.questions(r.user)
    val answers = questions.answers
    val prices = questions.jsonPrices
    val answerJson = Json.obj("answers" -> answers, "unsent" -> !draftAndSentIdentical, "prices" -> prices)
    Ok(questions.questionJson ++ answerJson)
  }

}
