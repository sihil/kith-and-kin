package helpers

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._
import models.Invite
import play.api.Logger
import play.api.mvc.RequestHeader

import scala.collection.JavaConverters._

object Email {
  def sendRsvpSummary(sesClient: AmazonSimpleEmailService, invite: Invite, total: Int, outstanding: Int)(implicit requestHeader: RequestHeader) = {
    invite.email.foreach { actualEmail =>
      sendEmail(sesClient, actualEmail, "Thanks for RSVPing",
        message = views.txt.rsvp.rsvpConfirmation(invite, total, outstanding).body
      )
    }
  }

  val from: String = "Kith & Kin Festival <no-reply@kithandkin.wedding>"

  def sendEmail(sesClient: AmazonSimpleEmailService, to: String, subject: String, message: String): Unit = {
    val destination = new Destination(List(to).asJava)
    val subjectContent = new Content(subject)
    val messageContent = new Content(message)
    val body = new Body(messageContent)
    val email = new Message(subjectContent, body)
    val request = new SendEmailRequest().withSource(from).withDestination(destination).withMessage(email)

    sesClient.sendEmail(request)
    Logger.logger.info(s"Sent email with subject $subject to $to")
  }

  def sendAdminEmail(sesClient: AmazonSimpleEmailService, subject: String, message: String, invite: Invite): Unit = {
    val inviteData =
      s"""
        |
        |Invite Data
        |-----------
        |ID: ${invite.id}
        |Adults: ${invite.adults.map(_.name).mkString(", ")}
        |Contact e-mail: ${invite.email}
      """.stripMargin
    sendEmail(sesClient, "info@kithandkin.wedding", subject, message + inviteData)
  }
}
