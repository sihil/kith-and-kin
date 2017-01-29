package models

import java.util.UUID

import org.joda.time.LocalDate

case class Adult(name: String)
case class Child(name: String, dob: LocalDate)
case class Invite(id: UUID, secret: Option[String], email: String, phone: String, address: String, priority: Int, adults: List[Adult], children: List[Child])
