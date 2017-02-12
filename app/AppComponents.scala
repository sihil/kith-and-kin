import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder
import controllers._
import db.{InviteRepository, PaymentRepository}
import filters.ForwardingFilter
import play.api.ApplicationLoader.Context
import play.api._
import play.api.libs.ws.ahc.AhcWSComponents
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import router.Routes

import scala.concurrent.ExecutionContext

class AppComponents(context: Context)
  extends BuiltInComponentsFromContext(context)
  with CSRFComponents
  with GzipFilterComponents
  with AhcWSComponents {

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new ForwardingFilter(context.environment.mode == Mode.Prod)
  )

  val baseUrl = if (environment.mode == Mode.Prod) {
    "https://www.kithandkin.wedding"
  } else {
    "http://127.0.0.1:9000"
  }

  val stage = if (environment.mode == Mode.Prod) {
    "PROD"
  } else {
    "DEV"
  }

  private val credentialsProviderChain = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider("kk"),
    InstanceProfileCredentialsProvider.getInstance()
  )
  val dynamoClient = AmazonDynamoDBClientBuilder.standard().
    withCredentials(credentialsProviderChain).withRegion("eu-west-2").build()

  val sesClient = AmazonSimpleEmailServiceClientBuilder.standard().
    withCredentials(credentialsProviderChain).withRegion("eu-west-1").build()

  implicit val operationContext: ExecutionContext = actorSystem.dispatchers.lookup("operation-context")

  val inviteRepository = new InviteRepository(dynamoClient, stage)
  val paymentRepository = new PaymentRepository(dynamoClient, stage)

  val kithKinController = new KithAndKinController()
  val rsvpController = new RsvpController(inviteRepository, paymentRepository, sesClient)
  val adminController = new AdminController(wsClient, baseUrl, inviteRepository)
  val paymentsController = new Payments(inviteRepository, paymentRepository)
  val assets = new Assets(httpErrorHandler)

  val router = new Routes(httpErrorHandler, kithKinController, rsvpController, paymentsController, adminController, assets)
}