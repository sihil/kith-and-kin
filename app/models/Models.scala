package models

import java.util.UUID

import org.joda.time.{DateTime, LocalDate, Period}
import ca.mrvisser.sealerate

object KithAndKinFestival {
  val fri = new LocalDate(2017, 8, 4)
}

object Accommodation {
  val OWN_TENT = "ownTent"
  val CAMPER = "camper"
  val CARAVAN = "caravan"
  val BELL_TENT = "belltent"
  val OFF_SITE = "offsite"
  val all = Seq(OWN_TENT, CAMPER, CARAVAN, BELL_TENT, OFF_SITE)
}

sealed trait GetInvolvedChoice {
  def key: String
  def name: String
}
object GetInvolvedChoice {
  case object Activites extends GetInvolvedChoice {
    val key = "activities"
    val name = "Activities"
  }
  case object MusicAndArts extends GetInvolvedChoice {
    val key = "musicAndArts"
    val name = "Music and arts"
  }
  case object Kids extends GetInvolvedChoice {
    val key = "kids"
    val name = "Kids"
  }
  case object Food extends GetInvolvedChoice {
    val key = "food"
    val name = "Food"
  }
  case object SetupAndLogistics extends GetInvolvedChoice {
    val key = "setupAndLogistics"
    val name = "Setup & logistics"
  }
  case object Other extends GetInvolvedChoice {
    val key = "other"
    val name = "Other"
  }
  def values: List[GetInvolvedChoice] = List(Activites, MusicAndArts, Kids, Food, SetupAndLogistics, Other)
  def allValues: Set[GetInvolvedChoice] = sealerate.values[GetInvolvedChoice]
  assert(values.toSet == allValues, "Not all case objects in values list")
}

case class Rsvp(
  coming: Option[Boolean] = None,
  everyone: Option[Boolean] = None,
  /* This is badly named, due to a bug this is actually the list of those who CAN come */
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
) {
  lazy val onsite: Boolean = accommodation.nonEmpty && !accommodation.contains("offsite")
  lazy val breakfast: Boolean = onsite || offSiteHavingBreakfast.contains(true)
}

case class StripePayment(stripeToken: String, charged: Boolean, stripeId: Option[String] = None, error: Option[String] = None)
case class BankTransfer(reference: String, received: Boolean)
case class Payment(id: UUID, date: DateTime, update: Int = 0, inviteId: UUID, amount: Int, stripePayment: Option[StripePayment] = None, bankTransfer: Option[BankTransfer] = None){
  val paymentType: String = if (stripePayment.nonEmpty) "Card" else if (bankTransfer.nonEmpty) "Bank transfer" else "Unknown"
  val confirmed: Boolean = stripePayment.map(_.charged).orElse(bankTransfer.map(_.received)).getOrElse(false)
}

sealed trait Person {
  def name:String
}
case class Adult(name: String) extends Person
case class Child(name: String, dob: LocalDate) extends Person {
  val age: Int = Period.fieldDifference(dob, KithAndKinFestival.fri).getYears
}

case class Invite(
  id: UUID,
  update: Int = 0,
  secret: Option[String],
  email: Option[String],
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
  onTheHouse: Option[Boolean] = None,
  editable: Option[Boolean] = None
) {
  def giveMeFirstNames = Invite.stringifyList(Invite.firstNames(adults, children))
  def giveMeAName = addressee.getOrElse(giveMeFirstNames)
  def number = adults.size + children.size
  def numberOfAdults = adults.size
  def isEditable = editable.contains(true)
}

object Invite {
  def firstName(name: String) = name.split(" ").head
  def firstNames(ps: List[Person]) = ps.map(p => firstName(p.name))
  def firstNames(as: List[Adult], cs: List[Child]): List[String] = firstNames(as ::: cs)
  def stringifyList(list: List[String]) = if (list.size <= 1) list.mkString(", ") else s"${list.init.mkString(", ")} and ${list.last}"
}

