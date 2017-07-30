import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder
import controllers._
import db.{EmailRepository, InviteRepository, PaymentRepository}
import filters.{AccessLoggingFilter, ForwardingFilter}
import helpers.EmailService
import models.{EmailTemplates, StripeKeys}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.EhCacheComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import router.Routes

import scala.concurrent.ExecutionContext

class AppComponents(context: Context)
  extends BuiltInComponentsFromContext(context)
  with CSRFComponents
  with GzipFilterComponents
  with AhcWSComponents
  with EhCacheComponents {

  override lazy val httpFilters = Seq(
    new AccessLoggingFilter(),
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

  val stripeKeys =
    if (environment.mode == Mode.Prod) {
      StripeKeys(
        publishable = "***REMOVED***",
        secret = "***REMOVED***"
      )
    } else {
      StripeKeys(
        publishable = "***REMOVED***",
        secret = "***REMOVED***"
      )
    }

  private val credentialsProviderChain = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider("kk"),
    InstanceProfileCredentialsProvider.getInstance()
  )
  val dynamoClient = AmazonDynamoDBClientBuilder.standard().
    withCredentials(credentialsProviderChain).withRegion("eu-west-2").build()

  /* note that this is eu-west-1 as SES isn't in London */
  val sesClient = AmazonSimpleEmailServiceClientBuilder.standard().
    withCredentials(credentialsProviderChain).withRegion("eu-west-1").build()
  val emailService = new EmailService(sesClient, stage)

  implicit val operationContext: ExecutionContext = actorSystem.dispatchers.lookup("operation-context")

  val inviteRepository = new InviteRepository(dynamoClient, stage)
  val paymentRepository = new PaymentRepository(dynamoClient, stage)
  val emailRepository = new EmailRepository(dynamoClient, stage)

  val emailTemplates = new EmailTemplates(paymentRepository)

  val kithKinController = new KithAndKinController()
  val rsvpController = new RsvpController(inviteRepository, paymentRepository, emailService, operationContext, environment.mode)
  val adminController = new AdminController(wsClient, baseUrl, inviteRepository, paymentRepository, emailService, emailRepository, emailTemplates, actorSystem)
  val paymentsController = new Payments(inviteRepository, paymentRepository, emailService, stripeKeys)
  val assets = new Assets(httpErrorHandler)

  val router = new Routes(httpErrorHandler, kithKinController, rsvpController, paymentsController, adminController, assets)
}