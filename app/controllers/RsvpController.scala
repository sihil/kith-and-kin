package controllers

import java.util.UUID

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import db.InviteRepository
import helpers.{Email, RsvpCookie, RsvpId, Secret}
import models.Invite
import play.api.Logger
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc.{Action, Controller, RequestHeader}

class RsvpController(inviteRepository: InviteRepository, sesClient: AmazonSimpleEmailService) extends Controller {
  def inviteFrom(request: RequestHeader) = RsvpCookie.parse(request.cookies).flatMap { rsvpId =>
    inviteRepository.getInvite(rsvpId.id).filter(RsvpCookie.valid(_, rsvpId))
  }
  object RsvpLogin extends AuthenticatedBuilder[Invite](inviteFrom, _ => Redirect(routes.RsvpController.start()))

  def start = Action { request =>
    RsvpCookie.parse(request.cookies) match {
      case Some(rsvpId)
        if inviteRepository.getInvite(rsvpId.id).exists(RsvpCookie.valid(_, rsvpId)) =>
        Redirect(routes.RsvpController.details())
      case None => Ok(views.html.rsvp(request))
    }
  }

  def lookup = Action { request =>
    val contact = request.body.asFormUrlEncoded.flatMap(_.get("contact").toSeq.flatten.headOption)
    contact match {
      case Some(email) if email.contains("@") =>
        // lookup e-mail in DB
        val list = inviteRepository.getInviteList
        val maybeInvite = list.find(_.email.trim.equalsIgnoreCase(email.trim))
        Logger.logger.info(s"$email $list $maybeInvite")
        maybeInvite match {
          case Some(invite) =>
            // generate a new secret
            val secret = Secret.newSecret()
            val withNewSecret = invite.copy(secret = Some(secret))
            inviteRepository.putInvite(withNewSecret)

            // now send e-mail to user with magic URL
            val message =
              s"""
                |Hi there!
                |
                |We've found your invite - please use the link below to access all areas and continue with your RSVP!
                |
                |${routes.RsvpController.login(invite.id.toString, secret).absoluteURL(request.secure, request.host)}
                |
                |You can use the link above as many times as you need it.
                |
                |Much love,
                |Simon & Christina
              """.stripMargin
            Email.sendEmail(sesClient, invite.email, "Kith & Kin RSVP information", message)

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

      case Some(phone) => NotImplemented("Phone support not implemented yet")
      case None => Redirect(routes.RsvpController.start())
    }
  }

  def sentMessage = Action { request =>
    val contactType = request.flash.data("contactType")
    Ok(views.html.sentMessage(contactType))
  }

  def notRight = Action { request =>
    val title = request.flash.data("title")
    val message = request.flash.data("message")
    Ok(views.html.notRight(title, message))
  }

  def login(idString: String, secret: String) = Action { request =>
    val id = UUID.fromString(idString)
    inviteRepository.getInvite(id) match {
      case Some(invite) if invite.secret.contains(secret) =>
        val loginCookie = RsvpCookie.make(RsvpId(id, secret), request.secure)
        Redirect(routes.RsvpController.start()).withCookies(loginCookie)
      case Some(invite) if RsvpCookie.parse(request.cookies).exists(RsvpCookie.valid(invite, _)) =>
        Redirect(routes.RsvpController.details())
      case _ =>
        Redirect(routes.RsvpController.notRight()).
          flashing(
            "title" -> "Oh no, your link is no longer valid...",
            "message" -> s"I'm sorry, the link you just used isn't valid. Make sure it is the most recent link you've been sent (requesting a new link makes all old links stop working)."
          )
    }
  }

  def details = RsvpLogin { request =>
    Ok(views.html.rsvpDetails(request.user))
  }
}
