package db

import java.util.UUID

import cats.syntax.either._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model.{ConditionalCheckFailedException, PutItemResult}
import com.gu.scanamo._
import models.{Invite, Payment}

class PaymentRepository(val client: AmazonDynamoDB, val stage: String) extends DynamoRepository {
  val tablePrefix = "kk-payments"

  val table = Table[Payment](tableName)

  import com.gu.scanamo.syntax._

  def putPayment(payment: Payment): Either[ConditionalCheckFailedException, PutItemResult] = {
    if (payment.update > 0) {
      exec(table.given('update -> payment.update).put(payment.copy(update = payment.update + 1))).logError
    } else {
      Right(exec(table.put(payment.copy(update = payment.update + 1))))
    }
  }

  def getPayment(id: UUID): Option[Payment] = exec(table.consistently.get('id -> id)).flatMap(_.logError.toOption)
  def getPaymentList: Iterable[Payment] = exec(table.scan()).flatMap(_.logError.toOption)
  def getPaymentsForInvite(invite: Invite): Iterable[Payment] = exec(table.consistently.scan()).flatMap(_.logError.toOption).filter(_.inviteId == invite.id)
}