package db

import cats.syntax.either._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, PutItemResult}
import com.gu.scanamo._
import models.Email

class EmailRepository(val client: AmazonDynamoDB, val stage: String) extends DynamoRepository {
  val tablePrefix = "kk-email"

  val table = Table[Email](tableName)

  import com.gu.scanamo.syntax._

  def putEmail(email: Email): Either[ConditionalCheckFailedException, PutItemResult] = {
    if (email.update > 0) {
      exec(table.given('update -> email.update).put(email.copy(update = email.update + 1))).logError
    } else {
      Right(exec(table.put(email)))
    }
  }

  def getEmail(id: String): Option[Email] = exec(table.consistently.get('id -> id)).flatMap(_.logError.toOption)
  def getEmailList: Iterable[Email] = exec(table.scan()).flatMap(_.logError.toOption)
}