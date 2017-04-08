package helpers

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._
import models.{EmailTemplate, Invite}
import play.api.Logger
import play.api.mvc.RequestHeader

import scala.collection.JavaConverters._
import scala.language.postfixOps

case class AWSEmail(to: String, subject: String, message: String, htmlMessage: Option[String] = None)

object AWSEmail {
  def fromTemplate(template: EmailTemplate, invites: Seq[Invite])(implicit requestHeader: RequestHeader): Seq[(Invite, AWSEmail)] = {
    val recipients = invites.filter(template.recipientSelector)
    recipients.flatMap{ invite =>
      invite.email.map { hasEmail =>
        invite -> AWSEmail(hasEmail, template.subject(invite), template.text(invite), template.html(invite))
      }
    }
  }
}

class EmailService(sesClient:AmazonSimpleEmailService, stage: String) {
  val sendEmails = stage == "PROD"

  def sendRsvpSummary(invite: Invite, total: Int, outstanding: Int)(implicit requestHeader: RequestHeader) = {
    invite.email.foreach { actualEmail =>
      sendEmail(AWSEmail(actualEmail, "Thanks for RSVPing",
        message = views.txt.rsvp.rsvpConfirmation(invite, total, outstanding).body
      ))
    }
  }

  val from: String = "Kith & Kin Festival <info@kithandkin.wedding>"

  def sendEmail(email: AWSEmail): Option[SendEmailResult] = {
    if (sendEmails || email.to == "simon@hildrew.net") {
      val destination = new Destination(List(email.to).asJava)
      val subjectContent = new Content(email.subject)
      val messageContent = new Content(email.message)
      val htmlMessageContent = email.htmlMessage.map(new Content(_))
      val body = new Body(messageContent).withHtml(htmlMessageContent.orNull)
      val message = new Message(subjectContent, body)
      val request = new SendEmailRequest().withSource(from).withDestination(destination).withMessage(message)

      val sendEmailResult = sesClient.sendEmail(request)
      Logger.logger.info(s"Sent email with subject ${email.subject} to ${email.to}")
      Some(sendEmailResult)
    } else {
      Logger.logger.info(s"Not PROD stage, skipping sending e-mail with subject ${email.subject} to ${email.to}")
      None
    }
  }

  def sendAdminEmail(subject: String, message: String, invite: Invite): Unit = {
    val inviteData =
      s"""
        |
        |Invite Data
        |-----------
        |ID: ${invite.id}
        |Adults: ${invite.adults.map(_.name).mkString(", ")}
        |Contact e-mail: ${invite.email}
      """.stripMargin
    sendEmail(AWSEmail("info@kithandkin.wedding", subject, message + inviteData))
  }
}
