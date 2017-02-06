package models

import java.util.UUID

import org.joda.time.{DateTime, LocalDate}

case class Rsvp(
  coming: Option[Boolean] = None,
  everyone: Option[Boolean] = None,
  hookup: Option[Boolean] = None,
  bellTentSharing: Option[Int] = None,
  bellTentBedding: Option[Boolean] = None,
  message: Option[String] = None,
  arrival: Option[String] = None,
  departure: Option[String] = None,
  accommodation: Option[String] = None,
  offSiteLocation: Option[String] = None,
  offSiteHavingBreakfast: Option[Boolean] = None
)

case class Payment(amount: Double, authCode: String, successful: Boolean)

case class Adult(name: String)
case class Child(name: String, dob: LocalDate)
case class Invite(
  id: UUID,
  update: Int,
  secret: Option[String],
  email: String,
  emailPreferred: Boolean,
  address: Option[String],
  priority: Int,
  addressee: Option[String],
  adults: List[Adult],
  children: List[Child],
  note: String,
  lastLoggedIn: Option[DateTime],
  sent: Boolean = false,
  draftRsvp: Option[Rsvp] = None,
  rsvp: Option[Rsvp] = None,
  payments: List[Payment] = Nil
) {
  def firstName(name: String) = name.split(" ").head
  def firstNames: List[String] = (adults.map(_.name) ::: children.map(_.name)).map(firstName)
  def stringifyList(list: List[String]) = if (list.size <= 1) list.mkString(", ") else s"${list.init.mkString(", ")} and ${list.last}"
  def giveMeAName = addressee.getOrElse(stringifyList(firstNames))
}

