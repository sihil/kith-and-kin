package controllers

import java.util.UUID

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.softwaremill.quicklens._
import com.stripe.exception.CardException
import com.stripe.model.Charge
import com.stripe.net.RequestOptions
import db.{InviteRepository, PaymentRepository}
import helpers.{Email, RsvpAuth}
import models._
import org.joda.time.DateTime
import play.api.mvc.{Action, Controller}

import scala.collection.JavaConverters._
import scala.compat.Platform.EOL
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class Payments(val inviteRepository: InviteRepository, paymentRepository: PaymentRepository,
               sesClient: AmazonSimpleEmailService, stripeKeys: StripeKeys)
              (implicit context: ExecutionContext) extends Controller with RsvpAuth {
  def home = RsvpLogin { implicit request =>
    val questions = QuestionMaster.questions(request.user)
    val payments = paymentRepository.getPaymentsForInvite(request.user).toList
    val paid = payments.filter{_.stripePayment.forall(_.charged)}.map(_.amount).sum
    val response = questions.finalResponse
    response.breakdown.map { breakdown =>
      Ok(views.html.payments.paymentsHome(Some(request.user.email), breakdown, response.totalPrice, payments, paid, stripeKeys.publishable))
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
            val requestOptions = RequestOptions.builder.setApiKey(stripeKeys.secret).build()
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
              case ce: CardException =>
                // failure due to card verification
                Email.sendAdminEmail(sesClient, "Card payment error",
                  s"Got a $ce whilst trying to process payment.\nCode: ${ce.getCode}\nDecline: ${ce.getDeclineCode}", request.user)
                val failedPayment = newPayment.modify(_.stripePayment.each.error).setTo(Some(s"${ce.getMessage}/${ce.getCode}"))
                paymentRepository.putPayment(failedPayment)
                Redirect(routes.Payments.error()).flashing("error" -> s"Stripe encountered an error whilst trying to take your card payment. \nCode: ${ce.getCode} \nDecline reason: ${ce.getDeclineCode}")
              case NonFatal(ex) =>
                // other failure
                Email.sendAdminEmail(sesClient, "Card payment error",
                  s"Got a $ex whilst trying to process payment.\n${ex.getStackTrace.mkString("", EOL, EOL)}", request.user)
                Redirect(routes.Payments.error()).flashing("error" -> "We encountered an unexpected error whilst trying to take a card payment.")
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

  def error = Action { request =>
    val message = request.flash.get("error").getOrElse("")
    Ok(views.html.payments.cardError(message))
  }
}
