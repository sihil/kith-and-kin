package models

import java.util.UUID

import org.joda.time.{DateTime, LocalDate}

case class Adult(name: String)
case class Child(name: String, dob: LocalDate)
case class Invite(
  id: UUID,
  secret: Option[String],
  email: String,
  emailPreferred: Boolean,
  address: Option[String],
  priority: Int,
  adults: List[Adult],
  children: List[Child],
  note: String,
  lastLoggedIn: Option[DateTime]
)
