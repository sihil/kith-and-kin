package models

import cats.data.NonEmptyList
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import com.softwaremill.quicklens._
import controllers.routes

import scala.language.postfixOps

case class Answer[T](key: String, text: String, price: List[Item] = Nil, nextQuestion: Option[QuestionReference] = None,
                     colour: Option[String] = None)(val internalValue: T) {
  def next(question: QuestionReference): Answer[T] = this.copy(nextQuestion = Some(question))(internalValue)
  def jsonAnswer = JsonAnswer(key, text, nextQuestion.map(_.key), colour)
}
object Answer {
  implicit val writes = new Writes[Answer[_]] {
    override def writes(o: Answer[_]): JsValue = {
      val fields: Seq[(String, JsValueWrapper)] = Seq(
        "updateKey" -> o.key,
        "text" -> o.text
      )
      val colour: Option[(String, JsValueWrapper)] = o.colour.map(c => "colour" -> c)
      val question: Option[(String, JsValueWrapper)] = o.nextQuestion.map(q => "nextQuestion" -> q.key)
      Json.obj(fields ++ colour ++ question: _*)
    }
  }
}
trait QuestionReference {
  /* the unique key that identifies this question */
  def key: String
  /* The text in the question */
  def question: String
  /* help text to make the question easier to understand */
  def helpText: Option[String]
  /* All routes out */
  def allOnwardQuestions: List[QuestionReference]
  /* convert this question into Json ready to send to the client */
  def jsonQuestion: JsonQuestion
  /* Used to get a client answer for this question from the Rsvp */
  def answer: Rsvp => Option[JsValue]
  /* Used to apply an update to an Rsvp given the client answer */
  def update(rsvp: Rsvp, choice: JsValue): Rsvp
  /* Price */
  def calculatePrice: Rsvp => List[Item]
  def selectedOnwardQuestion: Rsvp => Option[QuestionReference]
}
trait Question[T] extends QuestionReference {
  /* Update an Rsvp from the internal type */
  def updateRsvp: (Rsvp, Option[T]) => Rsvp
  /* Get the internal type from the Rsvp */
  def fromRsvp: Rsvp => Option[T]
}
case class MultipleChoice[T](question: String, key: String, answers: List[Answer[T]], helpText: Option[String] = None,
  updateRsvp: (Rsvp, Option[T]) => Rsvp, fromRsvp: Rsvp => Option[T]) extends Question[T] {
  private def fromKey(key: String): Option[Answer[T]] = answers.find(_.key == key)
  def jsonQuestion = JsonQuestion(question, helpText, key, "multipleChoice", answers.map(_.jsonAnswer))
  def update(rsvp: Rsvp, choice: JsValue) = {
    updateRsvp(rsvp, choice.asOpt[String].flatMap { fromKey(_).map(_.internalValue) })
  }
  private def answerT = (rsvp: Rsvp) =>
    for {
      answerValue <- fromRsvp(rsvp)
      answer <- answers.find(_.internalValue == answerValue)
    } yield answer
  def answer = (rsvp: Rsvp) => answerT(rsvp).map(a => JsString(a.key))
  override def allOnwardQuestions: List[QuestionReference] = answers.flatMap(_.nextQuestion)
  override def calculatePrice = (rsvp: Rsvp) => answerT(rsvp).toList.flatMap(_.price)
  override def selectedOnwardQuestion = (rsvp: Rsvp) => answerT(rsvp).flatMap(_.nextQuestion)
}
case class Text(question: String, key: String, helpText: Option[String] = None, optional: Boolean = false,
                updateRsvp: (Rsvp, Option[String]) => Rsvp, fromRsvp: Rsvp => Option[String],
                nextQuestion: Option[QuestionReference] = None, calculatePrice: (Rsvp) => List[Item] = _ => Nil) extends Question[String] {
  override def jsonQuestion: JsonQuestion = JsonQuestion(question, helpText, key, "text", optional = Some(optional), nextQuestion = nextQuestion.map(_.key))
  override def answer = rsvp => fromRsvp(rsvp).map(a => JsString(a))
  override def update(rsvp: Rsvp, choice: JsValue): Rsvp = updateRsvp(rsvp, choice.asOpt[String])
  def next(question: QuestionReference): Text = this.copy(nextQuestion = Some(question))
  override def allOnwardQuestions: List[QuestionReference] = nextQuestion.toList
  override def selectedOnwardQuestion: (Rsvp) => Option[QuestionReference] = _ => nextQuestion
}

case class Selection[T](question: String, key: String, answers: List[Answer[T]], helpText: Option[String] = None,
  updateRsvp: (Rsvp, Option[List[(T, Boolean)]]) => Rsvp, fromRsvp: Rsvp => Option[List[(T, Boolean)]],
  nextQuestion: Option[QuestionReference] = None, calculatePrice: (Rsvp) => List[Item] = _ => Nil) extends Question[List[(T, Boolean)]] {
  private def fromKey(key: String): Option[Answer[T]] = answers.find(_.key == key)
  def next(question: QuestionReference): Selection[T] = this.copy(nextQuestion = Some(question))
  override def allOnwardQuestions: List[QuestionReference] = nextQuestion.toList
  override def jsonQuestion: JsonQuestion = JsonQuestion(question, helpText, key, "selection", answers.map(_.jsonAnswer), nextQuestion = nextQuestion.map(_.key))
  override def answer: (Rsvp) => Option[JsValue] = (rsvp:Rsvp) => {
    val selectedMap = for {
      (answerValue, selected) <- fromRsvp(rsvp).toList.flatten
      answer <- answers.find(_.internalValue == answerValue)
    } yield {
      answer.key -> selected
    }
    Some(Json.toJson(selectedMap.toMap))
  }
  override def update(rsvp: Rsvp, choice: JsValue): Rsvp = {
    val selected = choice.asOpt[Map[String, Boolean]].map { selectedMap =>
      for {
        (updateKey, selected) <- selectedMap.toList
        answer <- fromKey(updateKey)
      } yield {
        answer.internalValue -> selected
      }
    }
    updateRsvp(rsvp, selected)
  }
  override def selectedOnwardQuestion: (Rsvp) => Option[QuestionReference] = _ => nextQuestion
}

case class JsonAnswer(key: String, text: String, nextQuestion: Option[String], colour: Option[String])
object JsonAnswer {
  implicit val jsonAnswerFormats = Json.format[JsonAnswer]
}
case class JsonQuestion(question: String, helpText: Option[String], key: String, questionType: String, answers: List[JsonAnswer] = Nil, optional: Option[Boolean] = None, nextQuestion: Option[String] = None)
object JsonQuestion {
  implicit val jsonQuestionFormats = Json.format[JsonQuestion]
}

case class PriceBreakdown(desc: String, itemAmount: Int, subTotal: Int)

trait Response {
  def answers: Map[String, JsValue]
  def prices: List[(String, List[Item])]
  def breakdown: Option[NonEmptyList[PriceBreakdown]]
  def totalPrice: Int
  def jsonPrices: JsObject
}

trait Questions {
  def allQuestions: Seq[QuestionReference]
  def jsonQuestions: Seq[JsonQuestion]
  def questionMap: Map[String, JsonQuestion]
  def questionJson: JsObject
  def draftResponse: Response
  def finalResponse: Response
}

object QuestionMaster {
  val numbersToWords = Map(1 -> "one", 2 -> "both", 3 -> "all three", 4 -> "all four", 5 -> "all five", 6 -> "all six")

  def questions(invite: Invite): Questions = new Questions {
    val cantMakeIt = invite.draftRsvp.toList.flatMap(_.cantMakeIt)
    val pluralInvited = invite.number > 1
    val numberComing = invite.number - cantMakeIt.size
    val adultsComing = invite.adults.filterNot(adult => cantMakeIt.contains(adult.name)).size
    val pluralComing = numberComing > 1
    def cond(plural: Boolean, singleAnswer: String, pluralAnswer: String): String = if (plural) pluralAnswer else singleAnswer

    lazy val startPage = areYouComing
    lazy val areYouComing: Question[Boolean] = MultipleChoice("Can you make Kith & Kin?", "canYouMakeIt", List(
      Answer("yes", "Yes!!")(true).next(if (pluralInvited) everyoneComing else dietaryRequirements),
      Answer("no", "Sadly not")(false).next(cannaeCome)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.coming).setTo(answer), fromRsvp = _.coming)

    lazy val cannaeCome = Text("Send a message to Simon & Christina", "cannaeCome", optional = true, updateRsvp = (rsvp, message) => rsvp.modify(_.message).setTo(message), fromRsvp = _.message)

    lazy val everyoneComing: Question[Boolean] = MultipleChoice(s"We're assuming that ${numbersToWords(invite.number)} of you are coming along, is that correct?", "everyoneComing", List(
      Answer("yes", "Yes, the whole clan")(true).next(dietaryRequirements),
      Answer("no", "Some of the clan cannae make it")(false).next(whoCannaeCome)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.everyone).setTo(answer), fromRsvp = _.everyone)

    lazy val names = invite.adults.map(_.name) ::: invite.children.map(_.name)
    private val nameAnswers = names.zipWithIndex.map { case (name, index) => Answer(index.toString, name)(internalValue = name) }
    lazy val whoCannaeCome = Selection[String]("Oh no! Tell us who :(", "whoCannaeCome",
      nameAnswers,
      updateRsvp = (rsvp, maybeAnswer) => {
        val thoseWhoCannaeCome = maybeAnswer.map { answer =>
          answer
            .filter {case (_, selected) => !selected}
            .map    {case (name, _) => name}
        }
        rsvp.modify(_.cantMakeIt).setToIfDefined(thoseWhoCannaeCome)
      },
      fromRsvp = rsvp => {
        Some(names.map { name =>
          name -> !rsvp.cantMakeIt.contains(name)
        })
      }
    ).next(if(numberComing>0)dietaryRequirements else cannaeCome)

    lazy val dietaryRequirements: Question[Boolean] = MultipleChoice(s"Do ${cond(pluralComing, "", "any of ")}you have dietary requirements?", "dietaryYesNo", List(
      Answer("no", s"${cond(pluralComing, "I", "We")} eat anything")(false).next(accommodation),
      Answer("yes", s"Aye${cond(pluralComing, "", ", some of us")}")(true).next(dietaryDetails)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.haveDietaryRequirements).setTo(answer), fromRsvp = _.haveDietaryRequirements)

    lazy val dietaryDetails: Text = Text("Please give us details of what you can't eat", "dietaryDetails",
      updateRsvp = (rsvp, message) => rsvp.modify(_.dietaryDetails).setTo(message), fromRsvp = _.dietaryDetails,
      helpText = if(invite.number > 1) Some("Please provide individual details for everyone in your clan") else None
    ).next(accommodation)

    lazy val accommodation: Question[String] = MultipleChoice("Where are you planning to stay?", "accommodation", List(
      Answer("ownTent", "Own tent", price=List(PerAdult("Pitch and breakfast", 2500)))(Accommodation.OWN_TENT).next(arrival),
      Answer("camper", "Own Campervan", price=List(PerAdult("Pitch and breakfast", 2500)))(Accommodation.CAMPER).next(hookup),
      Answer("caravan", "Own Caravan", price=List(PerAdult("Pitch and breakfast", 2500)))(Accommodation.CARAVAN).next(hookup),
      Answer("belltent", "Bell Tent")(Accommodation.BELL_TENT).next(bellTentSharing),
      Answer("offsite", "Off Site")(Accommodation.OFF_SITE).next(offsiteLocation)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.accommodation).setTo(answer),
      fromRsvp = _.accommodation,
      helpText = Some(s"""Details of on-site accommodation is on the <a href="${routes.KithAndKinController.accommodation}">accommodation page</a>.""")
    )

    lazy val hookup: Question[Boolean] = MultipleChoice("Will you want an electrical hookup?", "hookup", List(
      Answer("yes", "Yes", price=List(Fixed("Electrical hookup", 5000)))(true).next(arrival),
      Answer("no", "No")(false).next(arrival)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.hookup).setTo(answer), fromRsvp = _.hookup)

    lazy val bellTentSharing: Question[Int] = MultipleChoice("How many people are you ideally planning to share with (total number in the bell tent)?", "bellTentSharing", List(
      Answer("1", "One", price=List(PerPerson("Bell tent hire", 15000), PerAdult("Bed and breakfast", 4000)))(1).next(bellTentBedding),
      Answer("2", "Two", price=List(PerPerson("Bell tent hire (half)", 7500), PerAdult("Bed and breakfast", 4000)))(2).next(bellTentBedding),
      Answer("3", "Three", price=List(PerPerson("Bell tent hire (third)", 5000), PerAdult("Bed and breakfast", 4000)))(3).next(bellTentBedding),
      Answer("4", "Four", price=List(PerPerson("Bell tent hire (quarter)", 3750), PerAdult("Bed and breakfast", 4000)))(4).next(bellTentBedding),
      Answer("5", "Five", price=List(PerPerson("Bell tent hire (fifth)", 3000), PerAdult("Bed and breakfast", 4000)))(5).next(bellTentBedding),
      Answer("6", "Six", price=List(PerPerson("Bell tent hire (sixth)", 2500), PerAdult("Bed and breakfast", 4000)))(6).next(bellTentBedding)
    ).drop(numberComing-1), updateRsvp = (rsvp, answer) => rsvp.modify(_.bellTentSharing).setTo(answer), fromRsvp = _.bellTentSharing,
      helpText = Some("Are there two of you and you want some privacy? Choose two. Is it just you and you're happy to share with others? Choose the number you'd like to share with and let us know (in the message box later) if you already have friends in mind."))

    lazy val bellTentBedding: Question[Int] = MultipleChoice("How many of you want proper bedding (duvet/pillow)?", "bellTentBedding", List(
      Answer("0", "None of us")(0).next(arrival),
      Answer("1", "One", price = List(Fixed("Bedding hire (1 person)", 1500)))(1).next(arrival),
      Answer("2", "Two", price = List(Fixed("Bedding hire (2 people)", 3000)))(2).next(arrival),
      Answer("3", "Three", price = List(Fixed("Bedding hire (3 people)", 4500)))(3).next(arrival),
      Answer("4", "Four", price = List(Fixed("Bedding hire (4 people)", 6000)))(4).next(arrival),
      Answer("5", "Five", price = List(Fixed("Bedding hire (5 people)", 7500)))(5).next(arrival),
      Answer("6", "Six", price = List(Fixed("Bedding hire (6 people)", 9000)))(6).next(arrival)
    ).take(numberComing+1), updateRsvp = (rsvp, answer) => rsvp.modify(_.bellTentBedding).setTo(answer), fromRsvp = _.bellTentBedding)

    lazy val offsiteLocation = Text("Where are you planning to stay offsite (distance and location)", "offsiteLocation", updateRsvp = (rsvp, location) => rsvp.modify(_.offSiteLocation).setTo(location), fromRsvp = _.offSiteLocation).next(offsiteBreakfast)

    lazy val offsiteBreakfast: Question[Boolean] = MultipleChoice("Do you want to join us for breakfast on site?", "onSiteBreakfast", List(
      Answer("yes", "Yes", List(PerAdult("Breakfasts", 1500)))(true).next(arrival),
      Answer("no", "No, thanks")(false).next(arrival)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.offSiteHavingBreakfast).setTo(answer),
      fromRsvp = _.offSiteHavingBreakfast)

    private val lunchCatering = PerAdult("Friday lunch catering", 1000)
    private val eveningCatering = PerAdult("Friday evening catering", 2000)
    lazy val arrival: Question[String] = MultipleChoice("When are you planning to arrive?", "arrival", List(
      Answer("thursEve", "Thursday evening", List(lunchCatering, eveningCatering))("thursEve").next(departure),
      Answer("friMorn", "Friday morning", List(lunchCatering, eveningCatering))("friMorn").next(departure),
      Answer("friLunch", "Friday lunchtime", List(lunchCatering, eveningCatering))("friLunch").next(departure),
      Answer("friAft", "Friday afternoon", List(eveningCatering))("friAft").next(departure),
      Answer("friEve", "Friday evening", List(eveningCatering))("friEve").next(departure),
      Answer("friLate", "Friday late")("friLate").next(departure)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.arrival).setTo(answer), fromRsvp = _.arrival,
      helpText = Some("We need rough arrival times to help plan catering and activities"))

    private val sunLunchCatering = PerAdult("Sunday lunch catering", 1500)
    lazy val departure: Question[String] = MultipleChoice("And when are you planning to leave?", "departure", List(
      Answer("sunMorn", "Sunday morning")("sunMorn").next(getInvolvedChoice),
      Answer("sunLunch", "Sunday lunchtime", List(sunLunchCatering))("sunLunch").next(getInvolvedChoice),
      Answer("sunAft", "Sunday afternoon", List(sunLunchCatering))("sunAft").next(getInvolvedChoice)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.departure).setTo(answer), fromRsvp = _.departure)

    lazy val getInvolvedChoice: Question[String] = MultipleChoice("Which area would you most like to get involved in?", "getInvolvedChoice", List(
      Answer("0", "Activities")("activities").next(getInvolved),
      Answer("1", "Music and arts")("musicAndArts").next(getInvolved),
      Answer("2", "Kids")("kids").next(getInvolved),
      Answer("3", "Food")("food").next(getInvolved),
      Answer("4", "Setup & logistics")("setupAndLogistics").next(getInvolved),
      Answer("5", "Other")("other").next(getInvolved)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.getInvolvedPreference).setTo(answer), fromRsvp = _.getInvolvedPreference,
      helpText = Some(
        s"""We'd like your participation in Kith & Kin Festival to be your gift to us. There are loads of ways for you to
           |contribute, have a look at the <a href=\"${routes.KithAndKinController.getInvolved()}\">get involved page</a>
           |for suggestions.
           |""".stripMargin)
    )

    lazy val getInvolved: Text = Text("How would you like to get involved?", "getInvolved",
      updateRsvp = (rsvp, answer) => rsvp.modify(_.getInvolved).setTo(answer),
      fromRsvp = _.getInvolved,
      helpText = Some(
        s"""If you have particular roles or activities in mind from the <a href=\"${routes.KithAndKinController.getInvolved()}\">get involved page</a>,
           |or specific skills you can offer then please let us know what they are here. Or, if you're happy
           |for us to find you something to do then tell us what you do (or don't) want to do or get involved in.
           |""".stripMargin)
    ).next(message)

    lazy val message = Text("Send a message to Simon & Christina", "message", optional = true, updateRsvp = (rsvp, message) => rsvp.modify(_.message).setTo(message), fromRsvp = _.message,
      helpText = Some("e.g. who you want to share tents with, more precise arrival and departure times or just a wee note saying how excited you are... "))

    override val allQuestions: Seq[QuestionReference] = {
      def rec(question: QuestionReference): Set[QuestionReference] =
        Set(question) ++ question.allOnwardQuestions.flatMap(rec)
      val allQuestions = rec(startPage)
      assert(allQuestions.size == allQuestions.map(_.key).size, "Something wrong with keys, a question update key has probably been used twice")
      allQuestions.toSeq
    }
    override val jsonQuestions: Seq[JsonQuestion] = allQuestions.map(_.jsonQuestion)
    override val questionMap: Map[String, JsonQuestion] = jsonQuestions.map(q => q.key -> q).toMap
    override val questionJson: JsObject = Json.obj(
      "questions" -> questionMap,
      "startPage" -> startPage.key
    )


    override def draftResponse = buildResponse(invite.draftRsvp)
    override def finalResponse = buildResponse(invite.rsvp)

    def buildResponse(maybeRsvp: Option[Rsvp]): Response = {
      val rsvp = maybeRsvp.getOrElse(Rsvp())
      new Response {
        override val answers = allQuestions.flatMap(q => q.answer(rsvp).map(q.key ->)).toMap

        override val prices = {
          def rec(questionReference: Option[QuestionReference], acc: List[(String, List[Item])] = Nil): List[(String, List[Item])] = {
            questionReference match {
              case None => acc
              case Some(ref) =>
                val questionReferencePrice = ref.key -> ref.calculatePrice(rsvp)
                val maybeNextQuestion = ref.selectedOnwardQuestion(rsvp)
                rec(maybeNextQuestion, questionReferencePrice :: acc)
            }
          }
          rec(Some(startPage)).reverse
        }

        override val breakdown = {
          NonEmptyList.fromList(prices.flatMap(_._2).map { price =>
            val desc = price.desc + price.english.map(e => s" - $e").getOrElse("")
            PriceBreakdown(desc, price.amount, Item.subTotal(price, numberComing, adultsComing))
          })
        }

        override val totalPrice = Item.total(prices.flatMap{case(_, priceList)=>priceList}, numberComing, adultsComing)

        override val jsonPrices = {
          implicit val priceWrites = Item.writes(numberComing, adultsComing)
          Json.obj(
            "total" -> totalPrice,
            "breakdown" -> Json.toJson(prices.toMap)
          )
        }
      }
    }
  }
}