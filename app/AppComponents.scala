import controllers.Assets
import filters.ForwardingFilter
import play.api._
import play.api.ApplicationLoader.Context
import router.Routes
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents

class AppComponents(context: Context)
  extends BuiltInComponentsFromContext(context)
  with CSRFComponents
  with GzipFilterComponents {

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new ForwardingFilter(context.environment.mode == Mode.Prod)
  )

  val kithKinController = new controllers.KithAndKinController()
  val assets = new Assets(httpErrorHandler)

  val router = new Routes(httpErrorHandler, kithKinController, assets)
}