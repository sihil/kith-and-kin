package controllers

import java.util.UUID

import com.softwaremill.quicklens._
import com.stripe.exception.CardException
import com.stripe.model.Charge
import com.stripe.net.RequestOptions
import db.{InviteRepository, PaymentRepository}
import helpers.RsvpAuth
import models.{BankTransfer, Payment, QuestionMaster, StripePayment}
import org.joda.time.DateTime
import play.api.mvc.Controller

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class Payments(val inviteRepository: InviteRepository, paymentRepository: PaymentRepository)
              (implicit context: ExecutionContext) extends Controller with RsvpAuth {
  val stripeSecretKey = "***REMOVED***"
  val stripePublishableKey = "***REMOVED***"

  def home = RsvpLogin { implicit request =>
    val questions = QuestionMaster.questions(request.user)
    val payments = paymentRepository.getPaymentsForInvite(request.user).toList
    val paid = payments.map(_.amount).sum
    val response = questions.finalResponse
    response.breakdown.map { breakdown =>
      Ok(views.html.payments.paymentsHome(Some(request.user.email), breakdown, response.totalPrice, payments, paid, stripePublishableKey))
    } getOrElse NotFound
  }

  def stripeForm = RsvpLogin.async { request =>
    request.body.asFormUrlEncoded.map { formData =>
      val maybeToken = formData.get("stripeToken").flatMap(_.headOption)
      val maybeAmount = formData.get("amount").flatMap(_.headOption.map(_.toInt))
      (maybeToken, maybeAmount) match {
        case (Some(token), Some(amount)) =>
          val newPayment = Payment(UUID.randomUUID(), new DateTime(), 0, request.user.id, amount, Some(StripePayment(token, charged = false)))
          Future {
            paymentRepository.putPayment(newPayment)
            val requestOptions = RequestOptions.builder.setApiKey(stripeSecretKey).build()
            val chargeParams: Map[String, AnyRef] = Map(
              "amount" -> new Integer(amount),
              "currency" -> "gbp",
              "description" -> "Accommodation and food",
              "source" -> token
            )
            try {
              val charge = Charge.create(chargeParams.asJava, requestOptions)
              val stripeChargeId = charge.getId
              val chargedPayment =
                newPayment
                  .modify(_.stripePayment.each.charged).setTo(true)
                  .modify(_.stripePayment.each.stripeId).setTo(Some(stripeChargeId))
              paymentRepository.putPayment(chargedPayment)
              Redirect(routes.Payments.home())
            } catch {
              // TODO better error handling of charge failure
              case ce: CardException =>
                BadRequest
            }
          }
        case _ =>
          Future.successful(Redirect(routes.Payments.home()))
      }
    } getOrElse Future.successful(BadRequest)
  }

  def bankTransfer = RsvpLogin.async { request =>
    request.body.asFormUrlEncoded.map { formData =>
      val maybeReference = formData.get("reference").flatMap(_.headOption)
      val maybeAmount = formData.get("amount").flatMap(_.headOption.map(_.toInt))
      (maybeReference, maybeAmount) match {
        case (Some(reference), Some(amount)) =>
          Future {
            val newPayment = Payment(UUID.randomUUID(), new DateTime(), 0, request.user.id, amount, bankTransfer = Some(BankTransfer(reference, false)))
            paymentRepository.putPayment(newPayment)
            Redirect(routes.Payments.home())
          }
        case _ => Future.successful(Redirect(routes.Payments.home()))
      }
    } getOrElse Future.successful(BadRequest)
  }
}
