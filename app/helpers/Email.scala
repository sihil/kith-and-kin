package helpers

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._

import scala.collection.JavaConverters._

object Email {
  val from: String = "Kith & Kin Festival <no-reply@kithandkin.wedding>"

  def sendEmail(sesClient: AmazonSimpleEmailService, to: String, subject: String, message: String): Unit = {
    val destination = new Destination(List(to).asJava)
    val subjectContent = new Content(subject)
    val messageContent = new Content(message)
    val body = new Body(messageContent)
    val email = new Message(subjectContent, body)
    val request = new SendEmailRequest().withSource(from).withDestination(destination).withMessage(email)

    sesClient.sendEmail(request)
  }
}
