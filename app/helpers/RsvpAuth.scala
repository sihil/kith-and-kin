package helpers

import java.util.UUID

import akka.agent.Agent
import com.gu.googleauth.{GoogleAuthConfig, UserIdentifier}
import controllers.{Whitelist, routes}
import db.InviteRepository
import models.Invite
import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc.{RequestHeader, Results}
import play.api.libs.concurrent.Execution.Implicits._

object RsvpAuth {
  val lastSeenAgent = Agent[Map[UUID, DateTime]](Map.empty)
}

trait RsvpAuth extends UserIdentifier with Results {

  def inviteRepository: InviteRepository

  override def authConfig: GoogleAuthConfig = GoogleAuthConfig.withNoDomainRestriction("","","",enforceValidity = false)

  def inviteFrom(request: RequestHeader): Option[Invite] = RsvpCookie.parse(request.cookies).flatMap { rsvpId =>
    val googleAuth = userIdentity(request).filter(user => Whitelist.users.contains(user.email))
    val invite = inviteRepository.getInvite(rsvpId.id)
    (invite, googleAuth) match {
      case (Some(i), _) if RsvpCookie.valid(i, rsvpId) =>
        Logger.logger.info(s"Valid user login for ${i.adults.head.name}")
        val now = new DateTime()
        Some(RsvpAuth.lastSeenAgent().get(i.id) match {
          // seen within last 15 minutes, do nothing
          case Some(lastSeen) if (now.getMillis - lastSeen.getMillis) < 1000 * 900 => i
          // either never seen or more than 10 mins
          case _ =>
            val updatedInvite = i.copy(lastLoggedIn = Some(now))
            // update DB and cache
            inviteRepository.putInvite(updatedInvite)
            RsvpAuth.lastSeenAgent.send(_ + (i.id -> now))
            updatedInvite
        })
      case (Some(i), Some(auth)) =>
        Logger.logger.warn(s"Invite for ${i.email} impersonated by ${auth.email}")
        Some(i)
      case _ => None
    }
  }
  object RsvpLogin extends AuthenticatedBuilder[Invite](inviteFrom, _ => Redirect(routes.RsvpController.start()))

}
