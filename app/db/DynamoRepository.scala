package db

import java.util.UUID

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException
import com.gu.scanamo.error.{DynamoReadError, ScanamoError}
import com.gu.scanamo.{DynamoFormat, Scanamo}
import com.gu.scanamo.ops.ScanamoOps
import org.joda.time.{DateTime, LocalDate}
import play.api.Logger

trait DynamoRepository {

  def client: AmazonDynamoDB
  def exec[A](ops: ScanamoOps[A]): A = Scanamo.exec(client)(ops)
  def tablePrefix: String

  def stage: String
  lazy val tableName = s"$tablePrefix-$stage"

  implicit class RichEitherScanamoError[E <: ScanamoError, T](either: Either[E, T]) {
    def logError: Either[E, T] = {
      either.left.foreach(error => Logger.logger.error(s"Scanamo operation encountered $error"))
      either
    }
  }

  implicit class RichEitherScanamoException[T](either: Either[ConditionalCheckFailedException, T]) {
    def logError: Either[ConditionalCheckFailedException, T] = {
      either.left.foreach(error => Logger.logger.error(s"Scanamo put operation encountered check failure", error))
      either
    }
  }

  implicit val uuidFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val jodaDateTimeStringFormat =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](DateTime.parse)(_.toString)
  implicit val jodaDateStringFormat =
    DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse)(_.toString)

}