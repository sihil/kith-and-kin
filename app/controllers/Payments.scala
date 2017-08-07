package controllers

import java.util.UUID

import com.softwaremill.quicklens._
import com.stripe.exception.CardException
import com.stripe.model.Charge
import com.stripe.net.RequestOptions
import db.{InviteRepository, PaymentRepository}
import helpers.{AWSEmail, EmailService, RsvpAuth, RsvpCookie}
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc.{Action, Controller}

import scala.collection.JavaConverters._
import scala.compat.Platform.EOL
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import helpers._

class Payments(val inviteRepository: InviteRepository, paymentRepository: PaymentRepository,
               emailService: EmailService, stripeKeys: StripeKeys)
              (implicit context: ExecutionContext) extends Controller with RsvpAuth {
  def home = RsvpLogin { implicit request =>
    val invite = request.user.invite
    val questions = QuestionMaster.questions(invite, _.rsvp)
    val payments = paymentRepository.getPaymentsForInvite(invite).toList
    val paid = payments.filter{_.stripePayment.forall(_.charged)}.map(_.amount).sum
    questions.breakdown.map { breakdown =>
      Ok(views.html.payments.paymentsHome(invite.email, breakdown, questions.totalPrice, payments, paid, stripeKeys.publishable))
    } getOrElse Ok(views.html.payments.noPayment(invite.onTheHouse.contains(true)))
  }

  def fatalError[T](summary: String, message: String, invite: Option[Invite], adminOnlyMessage: Option[String] = None)(recovery: => T) = {
    emailService.sendAdminEmail(summary, message + adminOnlyMessage.map("\n"+_).getOrElse(""), invite)
    Logger.logger.warn(s"Logging fatal error. Summary: $summary Message: $message ID: ${invite.map(_.id).getOrElse("UNKNOWN")}")
    try { recovery } catch { case NonFatal(e) => Logger.logger.error("Unexpected exception whilst logging error", e) }
    Redirect(routes.Payments.error()).flashing("summary" -> summary, "message" -> message)
  }

  def stripeForm = RsvpLogin.async { request =>
    val invite = request.user.invite
    request.body.asFormUrlEncoded.map { formData =>
      val maybeToken = formData.get("stripeToken").flatMap(_.headOption)
      val maybeAmount = formData.get("amount").flatMap(_.headOption.map(_.toInt))
      (maybeToken, maybeAmount) match {
        case (Some(token), Some(amount)) =>
          val newPayment = Payment(UUID.randomUUID(), new DateTime(), 0, invite.id, amount, Some(StripePayment(token, charged = false)))
          Future {
            paymentRepository.putPayment(newPayment) match {
              case Left(_) =>
                fatalError("Storing payment failed", "Something went badly wrong whilst saving your payment", Some(invite),
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
                        Some(invite), Some(s"stripe payment ID $token for $amount")){}
                  }
                } catch {
                  case ce: CardException =>
                    // failure due to card verification
                    fatalError(
                      summary = "Card payment error",
                      message = s"Stripe encountered an error ($ce) whilst trying to process your payment.\nCode: ${ce.getCode}\nDecline reason: ${ce.getDeclineCode}",
                      invite = Some(invite),
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
                      invite = Some(invite),
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
    val invite = request.user.invite
    request.body.asFormUrlEncoded.map { formData =>
      val maybeReference = formData.get("reference").flatMap(_.headOption)
      val maybeAmount = formData.get("amount").flatMap(_.headOption.map(_.toInt))
      (maybeReference, maybeAmount) match {
        case (Some(reference), Some(amount)) =>
          Future {
            val newPayment = Payment(UUID.randomUUID(), new DateTime(), 0, invite.id, amount, bankTransfer = Some(BankTransfer(reference, false)))
            paymentRepository.putPayment(newPayment) match {
              case Right(_) => Redirect(routes.Payments.home())
              case Left(_) => fatalError(
                summary = "Bank transfer save failed",
                message = "We had a problem storing the bank transfer reference, please try again",
                invite = Some(invite)
              ){}
            }
          }
        case _ => Future.successful(Redirect(routes.Payments.home()))
      }
    } getOrElse Future.successful(BadRequest)
  }

  def honeymoon = Action { implicit request =>
    Ok(views.html.payments.honeymoon())
  }

  def honeymoonAnyAmount = Action { implicit request =>
    request.body.asFormUrlEncoded.flatMap { formData =>
      for {
        amount <- formData.get("amount_sterling").flatMap(_.headOption.map(_.toFloat))
        method <- formData.get("method").flatMap(_.headOption)
      } yield {
        val isTransfer = method == "transfer"
        Redirect(routes.Payments.honeymoonContribution(Math.round(amount*100), isTransfer, "Any amount"))
      }
    } getOrElse BadRequest
  }

  def honeymoonStripeForm = Action.async { request =>
    val invite = for {
      rsvpId <- RsvpCookie.parse(request.cookies)
      invite <- inviteRepository.getInvite(rsvpId.id)
    } yield invite
    request.body.asFormUrlEncoded.map { formData =>
      val result = for {
        who <- formData.get("who").flatMap(_.headOption)
        message <- formData.get("message").flatMap(_.headOption)
        amount <- formData.get("amount").flatMap(_.headOption.map(_.toInt))
        title <- formData.get("title").flatMap(_.headOption)
        token <- formData.get("stripeToken").flatMap(_.headOption)
      } yield {
        Future {
          val requestOptions = RequestOptions.builder.setApiKey(stripeKeys.secret).build()
          val chargeParams: Map[String, AnyRef] = Map(
            "amount" -> new Integer(amount),
            "currency" -> "gbp",
            "description" -> "Honeymoon gift",
            "source" -> token
          )
          try {
            val charge = Charge.create(chargeParams.asJava, requestOptions)
            val stripeChargeId = charge.getId
            val emailMessage =
              s"""You've been sent a honeymoon gift of ${amount.sterling(true)} by $who for $title!
                 |It was a card payment with the reference of $stripeChargeId
                 |Their message was:
                 |$message""".stripMargin
            Logger.logger.info(emailMessage)
            emailService.sendAdminEmail(
              "Honeymoon gift!",
              emailMessage,
              invite
            )
            Redirect(routes.Payments.honeymoonThanks()).flashing("honeymoon" -> "Thanks!")
          } catch {
            case ce: CardException =>
              // failure due to card verification
              fatalError(
                summary = "Card payment error",
                message = s"Stripe encountered an error ($ce) whilst trying to process your payment.\nCode: ${ce.getCode}\nDecline reason: ${ce.getDeclineCode}",
                invite = invite,
                adminOnlyMessage = Some(s"stripe payment ID $token for $amount")
              ) {}
            case NonFatal(ex) =>
              // other failure
              fatalError(
                summary = "Card payment error",
                message = s"Got a $ex whilst trying to process payment.\n${ex.getStackTrace.mkString("", EOL, EOL)}",
                invite = invite,
                adminOnlyMessage = Some(s"stripe payment ID $token for $amount")
              ) {}
          }
        }
      }
      result.getOrElse(Future.successful(Redirect(routes.Payments.home())))
    } getOrElse Future.successful(BadRequest)
  }


  def honeymoonBankTransfer = Action.async { request =>
    val invite = for {
      rsvpId <- RsvpCookie.parse(request.cookies)
      invite <- inviteRepository.getInvite(rsvpId.id)
    } yield invite
    request.body.asFormUrlEncoded.map { formData =>
      val result = for {
        who <- formData.get("who").flatMap(_.headOption)
        reference <- formData.get("reference").flatMap(_.headOption)
        message <- formData.get("message").flatMap(_.headOption)
        amount <- formData.get("amount").flatMap(_.headOption.map(_.toInt))
        title <- formData.get("title").flatMap(_.headOption)
      } yield {
        Future {
          val emailMessage =
            s"""You've been sent a honeymoon gift of ${amount.sterling(true)} by $who for $title!
               |It will be a bank transfer with the reference of $reference
               |Their message was:
               |$message""".stripMargin
          Logger.logger.info(emailMessage)
          emailService.sendAdminEmail(
            "Honeymoon gift!",
            emailMessage,
            invite
          )
          Redirect(routes.Payments.honeymoonThanks()).flashing("honeymoon" -> "Thanks!")
        }
      }
      result.getOrElse(Future.successful(Redirect(routes.Payments.home())))
    } getOrElse Future.successful(BadRequest)
  }


  def error = Action { request =>
    val summary = request.flash.get("summary").getOrElse("")
    val message = request.flash.get("message").getOrElse("")
    Ok(views.html.payments.cardError(summary, message))
  }

  def honeymoonContribution(amount: Int, transfer: Boolean, title: String) = Action { implicit request =>
    val invite = for {
      rsvpId <- RsvpCookie.parse(request.cookies)
      invite <- inviteRepository.getInvite(rsvpId.id)
    } yield invite

    Ok(views.html.payments.honeymoonPayment(invite, amount, title, stripeKeys.publishable, transfer))
  }

  def honeymoonThanks() = Action { implicit request =>
    Ok(views.html.payments.honeymoonThanks())
  }
}
