package models

import java.util.UUID

import org.joda.time.{DateTime, LocalDate}

case class Rsvp(
  coming: Option[Boolean] = None,
  everyone: Option[Boolean] = None,
  cantMakeIt: List[String] = Nil,
  haveDietaryRequirements: Option[Boolean] = None,
  dietaryDetails: Option[String] = None,
  hookup: Option[Boolean] = None,
  bellTentSharing: Option[Int] = None,
  bellTentBedding: Option[Int] = None,
  message: Option[String] = None,
  arrival: Option[String] = None,
  departure: Option[String] = None,
  accommodation: Option[String] = None,
  offSiteLocation: Option[String] = None,
  offSiteHavingBreakfast: Option[Boolean] = None,
  getInvolvedPreference: Option[String] = None,
  getInvolved: Option[String] = None
)

case class StripePayment(stripeToken: String, charged: Boolean, stripeId: Option[String] = None, error: Option[String] = None)
case class BankTransfer(reference: String, received: Boolean)
case class Payment(id: UUID, date: DateTime, update: Int, inviteId: UUID, amount: Int, stripePayment: Option[StripePayment] = None, bankTransfer: Option[BankTransfer] = None){
  val paymentType = if (stripePayment.nonEmpty) "Card" else if (bankTransfer.nonEmpty) "Bank transfer" else "Unknown"
  val confirmed = stripePayment.map(_.charged).orElse(bankTransfer.map(_.received)).getOrElse(false)
}


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
  def number = adults.size + children.size
  def numberOfAdults = adults.size
}

