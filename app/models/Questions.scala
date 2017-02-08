package models

import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import com.softwaremill.quicklens._

import scala.language.postfixOps

case class Answer[T](key: String, text: String, nextQuestion: Option[QuestionReference] = None, colour: Option[String] = None)(val internalValue: T) {
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
  def answer = (rsvp: Rsvp) => {
    for {
      answerValue <- fromRsvp(rsvp)
      answer <- answers.find(_.internalValue == answerValue)
    } yield { JsString(answer.key) }
  }
  override def allOnwardQuestions: List[QuestionReference] = answers.flatMap(_.nextQuestion)
}
case class Text(question: String, key: String, helpText: Option[String] = None, optional: Boolean = false, updateRsvp: (Rsvp, Option[String]) => Rsvp, fromRsvp: Rsvp => Option[String], nextQuestion: Option[QuestionReference] = None) extends Question[String] {
  override def jsonQuestion: JsonQuestion = JsonQuestion(question, helpText, key, "text", optional = Some(optional), nextQuestion = nextQuestion.map(_.key))
  override def answer = rsvp => fromRsvp(rsvp).map(a => JsString(a))
  override def update(rsvp: Rsvp, choice: JsValue): Rsvp = updateRsvp(rsvp, choice.asOpt[String])
  def next(question: QuestionReference): Text = this.copy(nextQuestion = Some(question))
  override def allOnwardQuestions: List[QuestionReference] = nextQuestion.toList
}

case class Selection[T](question: String, key: String, answers: List[Answer[T]], helpText: Option[String] = None,
  updateRsvp: (Rsvp, Option[List[(T, Boolean)]]) => Rsvp, fromRsvp: Rsvp => Option[List[(T, Boolean)]],
  nextQuestion: Option[QuestionReference] = None) extends Question[List[(T, Boolean)]] {
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
}

case class JsonAnswer(key: String, text: String, nextQuestion: Option[String], colour: Option[String])
object JsonAnswer {
  implicit val jsonAnswerFormats = Json.format[JsonAnswer]
}
case class JsonQuestion(question: String, helpText: Option[String], key: String, questionType: String, answers: List[JsonAnswer] = Nil, optional: Option[Boolean] = None, nextQuestion: Option[String] = None)
object JsonQuestion {
  implicit val jsonQuestionFormats = Json.format[JsonQuestion]
}

trait Questions {
  def allQuestions: Seq[QuestionReference]
  def jsonQuestions: Seq[JsonQuestion]
  def questionMap: Map[String, JsonQuestion]
  def questionJson: JsObject
  def answers: Map[String, JsValue]
}

object QuestionMaster {
  val numbersToWords = Map(1 -> "one", 2 -> "both", 3 -> "all three", 4 -> "all four", 5 -> "all five", 6 -> "all six")

  def questions(invite: Invite): Questions = new Questions {
    val pluralInvited = invite.number > 1
    val numberComing = invite.number - invite.draftRsvp.map(_.cantMakeIt.size).getOrElse(0)
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
      Answer("ownTent", "Own tent")("ownTent").next(arrival),
      Answer("camper", "Own Campervan")("camper").next(hookup),
      Answer("caravan", "Own Caravan")("caravan").next(hookup),
      Answer("belltent", "Bell Tent")("belltent").next(bellTentSharing),
      Answer("offsite", "Off Site")("offsite").next(offsiteLocation)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.accommodation).setTo(answer),
      fromRsvp = _.accommodation)
    lazy val hookup: Question[Boolean] = MultipleChoice("Will you want an electrical hookup?", "hookup", List(
      Answer("yes", "Yes")(true).next(arrival),
      Answer("no", "No")(false).next(arrival)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.hookup).setTo(answer), fromRsvp = _.hookup)

    lazy val bellTentSharing: Question[Int] = MultipleChoice("How many people are you ideally planning to share with (total number in the bell tent)?", "bellTentSharing", List(
      Answer("1", "One")(1).next(bellTentBedding),
      Answer("2", "Two")(2).next(bellTentBedding),
      Answer("3", "Three")(3).next(bellTentBedding),
      Answer("4", "Four")(4).next(bellTentBedding),
      Answer("5", "Five")(5).next(bellTentBedding),
      Answer("6", "Six")(6).next(bellTentBedding)
    ).drop(numberComing-1), updateRsvp = (rsvp, answer) => rsvp.modify(_.bellTentSharing).setTo(answer), fromRsvp = _.bellTentSharing,
      helpText = Some("Are there two of you and you want some privacy? Choose two. Is it just you and you're happy to share with others? Choose the number you'd like to share with and let us know (in the message box later) if you already have friends in mind."))

    lazy val bellTentBedding: Question[Boolean] = MultipleChoice("Do you want bedding (duvet/pillow)?", "bellTentBedding", List(
      Answer("yes", "Yes")(true).next(arrival),
      Answer("no", "No")(false).next(arrival)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.bellTentBedding).setTo(answer), fromRsvp = _.bellTentBedding)

    lazy val offsiteLocation = Text("Where are you planning to stay offsite (distance and location)", "offsiteLocation", updateRsvp = (rsvp, location) => rsvp.modify(_.offSiteLocation).setTo(location), fromRsvp = _.offSiteLocation).next(offsiteBreakfast)

    lazy val offsiteBreakfast: Question[Boolean] = MultipleChoice("Do you want to join us for breakfast on site?", "onSiteBreakfast", List(
      Answer("yes", "Yes")(true).next(arrival),
      Answer("no", "No, thanks")(false).next(arrival)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.offSiteHavingBreakfast).setTo(answer),
      fromRsvp = _.offSiteHavingBreakfast)

    lazy val arrival: Question[String] = MultipleChoice("When are you planning to arrive?", "arrival", List(
      Answer("thursEve", "Thursday evening")("thursEve").next(departure),
      Answer("friMorn", "Friday morning")("friMorn").next(departure),
      Answer("friLunch", "Friday lunchtime")("friLunch").next(departure),
      Answer("friAft", "Friday afternoon")("friAft").next(departure),
      Answer("friEve", "Friday evening")("friEve").next(departure)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.arrival).setTo(answer), fromRsvp = _.arrival)

    lazy val departure: Question[String] = MultipleChoice("And when are you planning to leave?", "departure", List(
      Answer("sunMorn", "Sunday morning")("sunMorn").next(message),
      Answer("sunLunch", "Sunday lunchtime")("sunLunch").next(message),
      Answer("sunAft", "Sunday afternoon")("sunAft").next(message)
    ), updateRsvp = (rsvp, answer) => rsvp.modify(_.departure).setTo(answer), fromRsvp = _.departure)

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
    override val answers: Map[String, JsValue] = {
      val rsvp = invite.draftRsvp.getOrElse(Rsvp())
      allQuestions.flatMap(q => q.answer(rsvp).map(q.key ->)).toMap
    }
  }
}