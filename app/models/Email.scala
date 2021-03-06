package models

import java.util.UUID

import db.PaymentRepository
import helpers.HtmlToPlainText
import org.joda.time.DateTime
import play.api.mvc.RequestHeader

case class Email(id: UUID, template: String, update: Int = 0, sentDate: DateTime, sentTo: List[String] = Nil)

class EmailTemplates(paymentRepository: PaymentRepository) {
  def allTemplates = List(
    ReminderEmailTemplate,
    SecondRoundEmailTemplate,
    RsvpLockDownEmailTemplate,
    new OneMonthEmailTemplate(paymentRepository),
    new OneWeekEmailTemplate(paymentRepository),
    new ChasePaymentsEmailTemplate(paymentRepository),
    ThanksEmailTemplate,
    MilestonesEmailTemplate
  )
}

trait EmailTemplate {
  def name: String
  def subject(invite: Invite): String
  def text(invite: Invite)(implicit request: RequestHeader): String
  def html(invite: Invite)(implicit request: RequestHeader): Option[String]
  def recipientSelector: Invite => Boolean
  def preSendCheck: Invite => Boolean = _ => true
  def postSendUpdate: Option[Invite => Option[Invite]] = None
}

object ReminderEmailTemplate extends EmailTemplate {
  override def name = "Gentle reminder"
  override def subject(invite: Invite) = "Reminder to RSVP for Kith & Kin festival"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = Some(views.html.email.reminder(invite).body)
  override def recipientSelector = _.rsvp.isEmpty
  override def preSendCheck = _.secret.nonEmpty
}

object SecondRoundEmailTemplate extends EmailTemplate {
  override def name = "Second round invites"
  override def subject(invite: Invite) = "Your invite to our Kith & Kin festival"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = Some(views.html.email.secondRoundInvite(invite).body)
  override def recipientSelector = !_.sent
  override def preSendCheck = _.secret.nonEmpty
  override def postSendUpdate = Some{ invite => Some(invite.copy(sent = true)) }
}

object RsvpLockDownEmailTemplate extends EmailTemplate {
  override def name = "RSVP lock down"
  override def subject(invite: Invite) = "Confirm your Kith & Kin RSVP!"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = Some(views.html.email.accommodationLockDown(invite).body)
  override def recipientSelector = i => !i.rsvp.flatMap(_.coming).contains(false)
  override def preSendCheck = _.secret.nonEmpty
}

class OneMonthEmailTemplate(paymentRepository: PaymentRepository) extends EmailTemplate {
  override def name = "One month comms"
  override def subject(invite: Invite) = "One month until Kith & Kin Festival!"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = {
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val payments = paymentRepository.getPaymentsForInvite(invite).toList
    val paid = payments.filter{p => p.stripePayment.forall(_.charged) || p.bankTransfer.forall(_.received)}.map(_.amount).sum
    Some(views.html.email.oneMonthComms(questions, payments, paid).body)
  }
  override def recipientSelector = i => i.rsvp.flatMap(_.coming).contains(true)
  override def preSendCheck = _.secret.nonEmpty
}

class OneWeekEmailTemplate(paymentRepository: PaymentRepository) extends EmailTemplate {
  override def name = "One week comms"
  override def subject(invite: Invite) = "One week until Kith & Kin festival!"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = {
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val payments = paymentRepository.getPaymentsForInvite(invite).toList
    val paid = payments.filter{p => p.stripePayment.forall(_.charged) || p.bankTransfer.forall(_.received)}.map(_.amount).sum
    Some(views.html.email.oneWeekComms(questions, payments, paid).body)
  }
  override def recipientSelector = i => i.rsvp.flatMap(_.coming).contains(true)
  override def preSendCheck = _.secret.nonEmpty
}

object ThanksEmailTemplate extends EmailTemplate {
  override def name = "Thanks & honeymoon"
  override def subject(invite: Invite) = "🌻 The best is yet to come! 🌻"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = {
    Some(views.html.email.thanksAndHoneymoon(invite).body)
  }
  override def recipientSelector = _ => true
}

object MilestonesEmailTemplate extends EmailTemplate {
  override def name = "Milestones"
  override def subject(invite: Invite) = "🌻 Milestones 🌻"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = {
    Some(views.html.email.milestonesParty(invite).body)
  }
  override def recipientSelector = _ => true
}

class ChasePaymentsEmailTemplate(paymentRepository: PaymentRepository) extends EmailTemplate {
  override def name = "Chase payments"
  override def subject(invite: Invite) = "🌻 Kith & Kin Payment Reminder"
  override def text(invite: Invite)(implicit request: RequestHeader) = html(invite).map(HtmlToPlainText.convert).get
  override def html(invite: Invite)(implicit request: RequestHeader) = {
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val paid = paymentRepository.getPaymentsForInvite(invite).filter(_.confirmed).map(_.amount).sum
    val maybeBreakdown = questions.breakdown
    maybeBreakdown.map { breakdown =>
      views.html.email.chasePayments(questions, paid, breakdown).body
    }
  }
  override def recipientSelector = invite => {
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val paid = paymentRepository.getPaymentsForInvite(invite).filter(_.confirmed).map(_.amount).sum
    paid < questions.totalPrice
  }
  override def preSendCheck = _.secret.nonEmpty
}