package controllers

import java.util.UUID

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.softwaremill.quicklens._
import db.{InviteRepository, PaymentRepository}
import helpers._
import models.{QuestionMaster, Rsvp}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, Controller}

class RsvpController(val inviteRepository: InviteRepository, paymentRepository: PaymentRepository, sesClient: AmazonSimpleEmailService) extends Controller with RsvpAuth {

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
            inviteRepository.putInvite(withNewSecret) match {
              case Right(_) =>
                // now send e-mail to user with magic URL
                val message =
                s"""|Hi ${invite.giveMeAName}!
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
                Email.sendEmail(sesClient, invite.email, "RSVP information", message) // now redirect
                Redirect(routes.RsvpController.sentMessage()).flashing("contactType" -> "e-mail address")
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
        val list = inviteRepository.getInviteList
        val maybeInvite = list.find{invite => invite.adults.map(_.name).contains(name)}
        maybeInvite match {
          case Some(invite) =>
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
                Email.sendEmail(sesClient, invite.email, "RSVP information", message)

                // now redirect
                Redirect(routes.RsvpController.sentMessage()).flashing("contactType" -> "e-mail address")
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

  def logout = Action {
    Redirect(routes.RsvpController.start()).discardingCookies(RsvpCookie.discard)
  }

  def details = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.details(request.user))
  }

  def rsvp = RsvpLogin { implicit request =>
    Ok(views.html.rsvp.rsvp())
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

    inviteRepository.putInvite(updatedInvite) match {
      case Right(_) =>
        val updatedQuestionJson = Some(updatedQuestions.questionJson).filterNot(_ == questions.questionJson)
        val updatedPricesJson =
        Some(updatedQuestions.draftResponse.jsonPrices)
        .filterNot(_ == questions.draftResponse.jsonPrices)
        .map(json => Json.obj("prices" -> json))
        val fields = Seq(Json.obj("result" -> "success")) ++ updatedQuestionJson ++ updatedPricesJson
        Ok(fields.reduce(_ ++ _))
      case Left(_) =>
        ServiceUnavailable("Failure when updating RSVP, try again")
    }

  }

  def complete = RsvpLogin { implicit request =>
    val questions = QuestionMaster.questions(request.user)
    val payments = paymentRepository.getPaymentsForInvite(request.user).toList
    val paid = payments.map(_.amount).sum
    val total = questions.finalResponse.totalPrice
    Ok(views.html.rsvp.thanks(request.user.rsvp.flatMap(_.coming).get, total, math.abs(total-paid)))
  }

  def questions = RsvpLogin { r =>
    val unsent = r.user.rsvp.isEmpty
    val modified = r.user.draftRsvp != r.user.rsvp
    val questions = QuestionMaster.questions(r.user)
    val answers = questions.draftResponse.answers
    val submittedAnswers = questions.finalResponse.answers
    val prices = questions.draftResponse.jsonPrices
    val answerJson = Json.obj(
      "answers" -> answers, "submittedAnswers" -> submittedAnswers,
      "unsent" -> unsent, "modified" -> modified, "prices" -> prices
    )
    Ok(questions.questionJson ++ answerJson)
  }

  def reset = RsvpLogin { r =>
    // set draft back to state of final
    val invite = r.user.modify(_.draftRsvp).setTo(r.user.rsvp)
    inviteRepository.putInvite(invite) match {
      case Right(_) => NoContent
      case Left(_) => ServiceUnavailable("reset failed, try again")
    }
  }

}
