import controllers.routes
import models.{Child, Invite, Person}
import play.api.mvc.Call

package object helpers {
  val currency = "£"
  implicit class IntCurrency(val int: Int) extends AnyVal {
    def sterling(canShorten:Boolean): String = {
      val sign = if (int < 0) "-" else ""
      val initialString = math.abs(int).toString
      val minimalString = if (initialString.length < 3) "0" * (3-initialString.length) + initialString else initialString
      val (pounds, pence) = minimalString.splitAt(minimalString.length-2)
      if (canShorten && pence == "00")
        s"$sign$currency$pounds"
      else
        s"$sign$currency$pounds.$pence"
    }
    def sterling: String = sterling(canShorten = false)
  }
  implicit class RichPersons(val persons: List[Person]) {
    def firstNames: String = {
      val names = persons.map(_.name.split(" ").head)
      if (names.size <= 1) names.mkString(", ") else s"${names.init.mkString(", ")} and ${names.last}"
    }
    def fullNames: String = {
      val names = persons.map(_.name)
      if (names.size <= 1) names.mkString(", ") else s"${names.init.mkString(", ")} and ${names.last}"
    }
  }
  val rsvpPages = Map(
    "RSVP" -> routes.RsvpController.rsvp(),
    "Payments" -> routes.Payments.home(),
    "Guestlist" -> routes.RsvpController.guestList()
  )
  implicit class RichInvite(invite: Invite) {
    def rsvpLink: Call = {
      invite.secret.map { secretCode =>
        routes.RsvpController.loginDefault(invite.id.toString, secretCode)
      } getOrElse {
        routes.RsvpController.start()
      }
    }
    def rsvpLink(dest: Call): Option[Call] = {
      invite.secret.flatMap { secretCode =>
        if (dest.method == "GET") {
          Some(routes.RsvpController.loginDest(invite.id.toString, secretCode, dest.url))
        } else None
      }
    }
  }
  implicit class RichChildren(children: Iterable[Child]) {
    def agesBreakdown: Map[Int, Int] = children.toSeq.groupBy(_.age).mapValues(_.length)

    def ageList: String = {
      agesBreakdown.toList.sortBy(-_._1).map{ case (age, number) => s"${number}x${age}yr"}.mkString(",")
    }
  }
}
