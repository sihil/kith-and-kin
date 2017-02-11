package db

import java.util.UUID

import cats.syntax.either._
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.gu.scanamo._
import models.{Invite, Payment}


class PaymentRepository(val client: AmazonDynamoDB, val stage: String) extends DynamoRepository {
  val tablePrefix = "kk-payments"

  val table = Table[Payment](tableName)

  import com.gu.scanamo.syntax._

  def putPayment(payment: Payment): Unit = {
    // TODO - enforce update ordering so we don't lose info - this seems to be implemented but not documented
    exec(table.put(payment.copy(update = payment.update + 1)))
  }

  def getPayment(id: UUID): Option[Payment] = exec(table.get('id -> id)).flatMap(_.toOption)
  def getPaymentList: Iterable[Payment] = exec(table.scan()).flatMap(_.toOption)
  def getPaymentsForInvite(invite: Invite): Iterable[Payment] = getPaymentList.filter(_.inviteId == invite.id)
}