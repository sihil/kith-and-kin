package helpers

import java.util.UUID

import models.Invite
import play.api.mvc.{Cookie, Cookies, DiscardingCookie}

case class RsvpId(id: UUID, secret: String)

object RsvpCookie {
  private val cookieName = "inviteLogin"

  val discard = DiscardingCookie(cookieName)
  def make(rsvpId: RsvpId, secure: Boolean) = Cookie(cookieName, s"${rsvpId.id}|${rsvpId.secret}", secure = secure)
  def parse(cookies: Cookies): Option[RsvpId] = cookies.get(cookieName).flatMap { cookie =>
    cookie.value.split("\\|").toList match {
      case id :: secret :: Nil => Some(RsvpId(UUID.fromString(id), secret))
      case _ => None
    }
  }
  def valid(invite: Invite, rsvpId: RsvpId): Boolean = {
    invite.id == rsvpId.id && invite.secret.contains(rsvpId.secret)
  }
}
