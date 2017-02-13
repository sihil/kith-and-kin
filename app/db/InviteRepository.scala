package db

import java.util.UUID

import cats.syntax.either._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, PutItemResult}
import com.gu.scanamo._
import models.Invite


class InviteRepository(val client: AmazonDynamoDB, val stage: String) extends DynamoRepository {
  val tablePrefix = "kk-invites"

  val table = Table[Invite](tableName)

  import com.gu.scanamo.syntax._

  def putInvite(invited: Invite): Either[ConditionalCheckFailedException, PutItemResult] = {
    if (invited.update > 0) {
      exec(table.given('update -> invited.update).put(invited.copy(update = invited.update + 1))).logError
    } else {
      Right(exec(table.put(invited)))
    }
  }

  def getInvite(id: UUID): Option[Invite] = exec(table.consistently.get('id -> id)).flatMap(_.logError.toOption)
  def getInviteList: Iterable[Invite] = exec(table.scan()).flatMap(_.logError.toOption)
}