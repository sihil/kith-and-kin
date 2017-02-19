package controllers

import java.util.UUID

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.softwaremill.quicklens._
import db.{InviteRepository, PaymentRepository}
import helpers._
import models.{QuestionMaster, Rsvp}
import play.api.Mode.Mode
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, Controller}

import scala.concurrent.{ExecutionContext, Future}

class RsvpController(val inviteRepository: InviteRepository, paymentRepository: PaymentRepository,
                     sesClient: AmazonSimpleEmailService, context: ExecutionContext, mode: Mode) extends Controller with RsvpAuth {

  def start = Action { request =>
    Ok(views.html.rsvp.start(request))
  }

  def lookup = Action { request =>
    val contact = request.body.asFormUrlEncoded.flatMap(_.get("contact").toSeq.flatten.headOption)
    contact match {
      case Some(email) if email.contains("@") =>
        // lookup e-mail in DB
        val list = inviteRepository.getInviteList.toList
        val maybeInvite = list.find(_.email.contains(email.trim.toLowerCase))
        maybeInvite match {
          case Some(invite) =>
            val email = invite.email.get // this is safe due to the find operation above
            // generate a new secret
            val secret = Secret.newSecret()
            val withNewSecret = invite.copy(secret = Some(secret))
            inviteRepository.putInvite(withNewSecret) match {
              case Right(_) =>
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
                Email.sendEmail(sesClient, email, "RSVP information", message)
                Redirect(routes.RsvpController.sentMessage(email))
              case Left(_) =>
                Redirect(routes.RsvpController.notRight()).
                  flashing(
                    "title" -> "Oh no, something went wrong...",
                    "message" -> s"Ummm, not sure what just happened, it's like I had you, but then lost you again. Can you try again?"
                  )
            }
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
        val list = inviteRepository.getInviteList.toList
        val maybeInvite = list.find{invite => invite.adults.map(_.name.toLowerCase).contains(name.toLowerCase)}
        maybeInvite match {
          case Some(invite) if invite.email.nonEmpty =>
            val email = invite.email.get // this is safe due to the pattern match guard
            // generate a new secret
            val secret = Secret.newSecret()
            val withNewSecret = invite.copy(secret = Some(secret))
            inviteRepository.putInvite(withNewSecret) match {
              case Right(_) =>
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
                Email.sendEmail(sesClient, email, "RSVP information", message)

                // now redirect
                Redirect(routes.RsvpController.sentMessage(email))
              case Left(_) =>
                Redirect(routes.RsvpController.notRight()).
                  flashing(
                    "title" -> "Oh no, something went wrong...",
                    "message" -> s"Ummm, not sure what just happened, it's like I had you, but then lost you again. Can you try again?"
                  )
            }

          case Some(invite) =>
            // invite found, but we don't have an e-mail address
            Redirect(routes.RsvpController.notRight()).
              flashing(
                "title" -> "Oh no, we don't have an e-mail address for you...",
                "message" -> s"We've found your invite! But for some reason we don't have an e-mail address for you. Get in touch and we'll sort something out!"
              )

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

  def sentMessage(emailAddress: String) = Action { request =>
    Ok(views.html.rsvp.sentMessage(emailAddress))
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

  def logout = Action {
    Redirect(routes.RsvpController.start()).discardingCookies(RsvpCookie.discard)
  }

  def details = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.details(request.user.invite))
  }

  def rsvp = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.rsvp())
  }

  def update(complete: Boolean) = RsvpLogin(parse.json) { implicit request =>
    val updateMap = request.body.as[Map[String, JsValue]]
    val invite = request.user.invite
    val currentRsvp = invite.draftRsvp.getOrElse(Rsvp())
    val questions = QuestionMaster.questions(invite, _.draftRsvp)
    val updatedRsvp = updateMap.foldLeft(currentRsvp) { case (rsvp, (question, answer)) =>
      questions.allQuestions.find(_.key==question).map { q =>
        q.update(rsvp, answer)
      }.getOrElse(rsvp)
    }
    val updatedInvite = if (complete)
      invite.modify(_.draftRsvp).setTo(Some(updatedRsvp)).modify(_.rsvp).setTo(Some(updatedRsvp))
    else
      invite.modify(_.draftRsvp).setTo(Some(updatedRsvp))


    inviteRepository.putInvite(updatedInvite) match {
      case Right(_) =>
        val updatedDraftQuestions = QuestionMaster.questions(updatedInvite, _.draftRsvp)
        val updatedQuestionJson = Some(updatedDraftQuestions.questionJson).filterNot(_ == questions.questionJson)
        val updatedPricesJson = Some(updatedDraftQuestions.jsonPrices)
                                  .filterNot(_ == questions.jsonPrices)
                                  .map(json => Json.obj("prices" -> json))
        val fields = Seq(Json.obj("result" -> "success")) ++ updatedQuestionJson ++ updatedPricesJson
        if (invite.rsvp.isEmpty && complete && request.user.realUser) {
          Future{
            val payments = paymentRepository.getPaymentsForInvite(invite).toList
            val paid = payments.map(_.amount).sum
            val total = updatedDraftQuestions.totalPrice // since we just copied the RSVP into both places we can use the draft questions
            Email.sendRsvpSummary(sesClient, updatedInvite, total, math.max(total-paid, 0))
          }(context)
        }
        Ok(fields.reduce(_ ++ _))
      case Left(_) =>
        ServiceUnavailable("Failure when updating RSVP, try again")
    }

  }

  def complete = RsvpLogin { implicit request =>
    val invite = request.user.invite
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val payments = paymentRepository.getPaymentsForInvite(invite).toList
    val paid = payments.map(_.amount).sum
    val total = questions.totalPrice
    Ok(views.html.rsvp.thanks(invite.rsvp.flatMap(_.coming).get, total, math.max(total-paid, 0)))
  }

  def questions = RsvpLogin { r =>
    val invite = r.user.invite
    val unsent = invite.rsvp.isEmpty
    val modified = invite.draftRsvp != invite.rsvp

    val questions = QuestionMaster.questions(invite, _.rsvp)
    val submittedAnswers = questions.answers

    val draftQuestions = QuestionMaster.questions(invite, _.draftRsvp)
    val answers = draftQuestions.answers
    val prices = draftQuestions.jsonPrices
    val answerJson = Json.obj(
      "answers" -> answers, "submittedAnswers" -> submittedAnswers,
      "unsent" -> unsent, "modified" -> modified, "prices" -> prices
    )
    Ok(draftQuestions.questionJson ++ answerJson)
  }

  def reset = RsvpLogin { r =>
    // set draft back to state of final
    val invite = r.user.invite.modify(_.draftRsvp).setTo(r.user.invite.rsvp)
    inviteRepository.putInvite(invite) match {
      case Right(_) => NoContent
      case Left(_) => ServiceUnavailable("reset failed, try again")
    }
  }

}
