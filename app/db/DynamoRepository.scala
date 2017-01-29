package db

import java.util.UUID

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.gu.scanamo.{DynamoFormat, Scanamo}
import com.gu.scanamo.ops.ScanamoOps
import org.joda.time.{DateTime, LocalDate}

trait DynamoRepository {

  def client: AmazonDynamoDB
  def exec[A](ops: ScanamoOps[A]): A = Scanamo.exec(client)(ops)
  def tablePrefix: String

  def stage: String
  lazy val tableName = s"$tablePrefix-$stage"

  implicit val uuidFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val jodaDateTimeStringFormat =
    DynamoFormat.coercedXmap[DateTime, String, IllegalArgumentException](DateTime.parse)(_.toString)
  implicit val jodaDateStringFormat =
    DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse)(_.toString)

}