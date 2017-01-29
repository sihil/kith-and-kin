package db

import java.util.UUID

import cats.syntax.either._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.gu.scanamo._
import models.Invite


class InviteRepository(val client: AmazonDynamoDB, val stage: String) extends DynamoRepository {
  val tablePrefix = "kk-invites"

  val table = Table[Invite](tableName)

  import com.gu.scanamo.syntax._

  def putInvite(invited: Invite): Unit = exec(table.put(invited))
  def getInvite(id: UUID): Option[Invite] = exec(table.get('id -> id)).flatMap(_.toOption)
  def deleteInvite(id: UUID): Unit = exec(table.delete('id -> id))
  def getInviteList: Iterable[Invite] = exec(table.scan()).flatMap(_.toOption)
}
