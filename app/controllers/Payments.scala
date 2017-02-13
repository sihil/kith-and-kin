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
import play.api.Logger
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
      Ok(views.html.payments.paymentsHome(request.user.email, breakdown, response.totalPrice, payments, paid, stripeKeys.publishable))
    } getOrElse NotFound
  }

  def fatalError[T](summary: String, message: String, invite: Invite, adminOnlyMessage: Option[String] = None)(recovery: => T) = {
    Email.sendAdminEmail(sesClient, summary, message + adminOnlyMessage.map("\n"+_).getOrElse(""), invite)
    Logger.logger.warn(s"Logging fatal error. Summary: $summary Message: $message ID: ${invite.id}")
    try { recovery } catch { case NonFatal(e) => Logger.logger.error("Unexpected exception whilst logging error", e) }
    Redirect(routes.Payments.error()).flashing("summary" -> summary, "message" -> message)
  }

  def stripeForm = RsvpLogin.async { request =>
    request.body.asFormUrlEncoded.map { formData =>
      val maybeToken = formData.get("stripeToken").flatMap(_.headOption)
      val maybeAmount = formData.get("amount").flatMap(_.headOption.map(_.toInt))
      (maybeToken, maybeAmount) match {
        case (Some(token), Some(amount)) =>
          val newPayment = Payment(UUID.randomUUID(), new DateTime(), 0, request.user.id, amount, Some(StripePayment(token, charged = false)))
          Future {
            paymentRepository.putPayment(newPayment) match {
              case Left(_) =>
                fatalError("Storing payment failed", "Something went badly wrong whilst saving your payment", request.user,
                  Some(s"stripe payment ID $token for $amount")){}
              case Right(_) =>
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
                  paymentRepository.putPayment(chargedPayment) match {
                    case Right(_) => Redirect(routes.Payments.home())
                    case Left(_) =>
                      fatalError("Storing payment failed", "Something went badly wrong whilst saving the fact that your payment succeeded",
                        request.user, Some(s"stripe payment ID $token for $amount")){}
                  }
                } catch {
                  case ce: CardException =>
                    // failure due to card verification
                    fatalError(
                      summary = "Card payment error",
                      message = s"Stripe encountered an error ($ce) whilst trying to process your payment.\nCode: ${ce.getCode}\nDecline reason: ${ce.getDeclineCode}",
                      invite = request.user,
                      adminOnlyMessage = Some(s"stripe payment ID $token for $amount")
                    ) {
                      val failedPayment = newPayment.modify(_.stripePayment.each.error).setTo(Some(s"${ce.getMessage}/${ce.getCode}"))
                      paymentRepository.putPayment(failedPayment)
                    }
                  case NonFatal(ex) =>
                    // other failure
                    fatalError(
                      summary = "Card payment error",
                      message = s"Got a $ex whilst trying to process payment.\n${ex.getStackTrace.mkString("", EOL, EOL)}",
                      invite = request.user,
                      adminOnlyMessage = Some(s"stripe payment ID $token for $amount")
                    ){}
                }
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
            paymentRepository.putPayment(newPayment) match {
              case Right(_) => Redirect(routes.Payments.home())
              case Left(_) => fatalError(
                summary = "Bank transfer save failed",
                message = "We had a problem storing the bank transfer reference, please try again",
                invite = request.user
              ){}
            }
          }
        case _ => Future.successful(Redirect(routes.Payments.home()))
      }
    } getOrElse Future.successful(BadRequest)
  }

  def error = Action { request =>
    val summary = request.flash.get("summary").getOrElse("")
    val message = request.flash.get("message").getOrElse("")
    Ok(views.html.payments.cardError(summary, message))
  }
}
