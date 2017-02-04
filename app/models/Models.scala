package models

import java.util.UUID

import org.joda.time.{DateTime, LocalDate}

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
  rsvp: Option[Rsvp] = None,
  payments: List[Payment] = Nil
) {
  def firstName(name: String) = name.split(" ").head
  def firstNames: List[String] = (adults.map(_.name) ::: children.map(_.name)).map(firstName)
  def stringifyList(list: List[String]) = if (list.size <= 1) list.mkString(", ") else s"${list.init.mkString(", ")} and ${list.last}"
  def giveMeAName = addressee.getOrElse(stringifyList(firstNames))
}

sealed trait Accommodation
case class OffSite(location: String, onSiteBreakfast: Boolean) extends Accommodation
case class OnSite(option: OnSiteOption) extends Accommodation

sealed trait OnSiteOption
case object OwnTent extends OnSiteOption
case object OwnCamper extends OnSiteOption
case object OwnCaravan extends OnSiteOption
case object BellTent extends OnSiteOption

case class Rsvp(coming: Boolean, message: String, arrival: DateTime, departure: DateTime, accommodation: Accommodation)

case class Payment(amount: Double, authCode: String, successful: Boolean)