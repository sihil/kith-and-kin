package models

import java.util.UUID

import helpers.HtmlToPlainText
import org.joda.time.DateTime
import play.api.mvc.RequestHeader

case class Email(id: UUID, template: String, update: Int = 0, sentDate: DateTime, sentTo: List[String] = Nil)

object EmailTemplate {
  def allTemplates = List(ReminderEmailTemplate, SecondRoundEmailTemplate)
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