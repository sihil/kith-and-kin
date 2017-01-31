package models

import java.util.UUID

import org.joda.time.LocalDate

case class Csv(
  timestamp: String,
  name: String,
  addressee: String,
  postalAddress: String,
  email: String,
  timbuktu: String,
  clanSize: String,
  partner: String,
  children: String,
  partnerWithChildren: String,
  childrenOnly: String,
  note: String
) {
  val KidRE = """^(.*) (\d\d\d\d-\d\d-\d\d)$""".r
  def nonEmptyToOption(string: String): Option[String] = if (string.trim.nonEmpty) Some(string.trim) else None
  def kidParse(row: String): Child = {
    row match {
      case KidRE(kid, date) => Child(kid, LocalDate.parse(date))
      case unparseable => Child(unparseable, new LocalDate())
    }
  }
  def toInvite: Option[Invite] = {
    if (email.trim.nonEmpty) {
      val adults = (List(name.trim) ++ nonEmptyToOption(partner) ++ nonEmptyToOption(partnerWithChildren)).map(Adult.apply)
      val kids = children.split("\n").filterNot(_.isEmpty).map(kidParse).toList
      Some(
        Invite(
          UUID.randomUUID(),
          None,
          email.trim.toLowerCase,
          timbuktu.trim.nonEmpty,
          nonEmptyToOption(postalAddress),
          0,
          adults,
          kids,
          s"$clanSize${nonEmptyToOption(note).map("\n"+_).getOrElse("")}",
          None
        )
      )
    } else {
      None
    }
  }
}