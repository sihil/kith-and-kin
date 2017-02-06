package models

import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsValue, Json, Writes}
import com.softwaremill.quicklens._
import org.joda.time.DateTime

case class Answer[T](updateKey: String, text: String, nextQuestion: Option[QuestionReference] = None, colour: Option[String] = None)(val internalValue: T) {
  def next(question: QuestionReference): Answer[T] = this.copy(nextQuestion = Some(question))(internalValue)
  def jsonAnswer = JsonAnswer(updateKey, text, nextQuestion.map(_.updateKey), colour)
}
object Answer {
  implicit val writes = new Writes[Answer[_]] {
    override def writes(o: Answer[_]): JsValue = {
      val fields: Seq[(String, JsValueWrapper)] = Seq(
        "updateKey" -> o.updateKey,
        "text" -> o.text
      )
      val colour: Option[(String, JsValueWrapper)] = o.colour.map(c => "colour" -> c)
      val question: Option[(String, JsValueWrapper)] = o.nextQuestion.map(q => "nextQuestion" -> q.updateKey)
      Json.obj(fields ++ colour ++ question: _*)
    }
  }
}
trait QuestionReference {
  /* the unique key that identifies this question */
  def updateKey: String
  /* The text in the question */
  def question: String
  /* help text to make the question easier to understand */
  def helpText: Option[String]
  /* All routes out */
  def allOnwardQuestions: List[QuestionReference]
  /* convert this question into Json ready to send to the client */
  def jsonQuestion: JsonQuestion
  /* Used to get a client answer for this question from the Rsvp */
  def answer: Rsvp => Option[String]
  /* Used to apply an update to an Rsvp given the client answer */
  def update(rsvp: Rsvp, choice: String): Rsvp
}
trait Question[T] extends QuestionReference {
  /* Update an Rsvp from the internal type */
  def updateRsvp: (Rsvp, Option[T]) => Rsvp
  /* Get the internal type from the Rsvp */
  def fromRsvp: Rsvp => Option[T]
}
case class MultipleChoice[T](question: String, updateKey: String, answers: List[Answer[T]], helpText: Option[String] = None, updateRsvp: (Rsvp, Option[T]) => Rsvp, fromRsvp: Rsvp => Option[T]) extends Question[T] {
  def fromKey(key: String): Option[Answer[T]] = answers.find(_.updateKey == key)
  def update(rsvp: Rsvp, choice: String) = updateRsvp(rsvp, fromKey(choice).map(_.internalValue))
  def jsonQuestion = JsonQuestion(question, helpText, updateKey, "multipleChoice", answers.map(_.jsonAnswer))
  def answer = (rsvp: Rsvp) => {
    for {
      answerValue <- fromRsvp(rsvp)
      answer <- answers.find(_.internalValue == answerValue)
    } yield { answer.updateKey }
  }
  override def allOnwardQuestions: List[QuestionReference] = answers.flatMap(_.nextQuestion)
}
case class Text(question: String, updateKey: String, helpText: Option[String] = None, updateRsvp: (Rsvp, Option[String]) => Rsvp, fromRsvp: Rsvp => Option[String], nextQuestion: Option[QuestionReference] = None) extends Question[String] {
  override def jsonQuestion: JsonQuestion = JsonQuestion(question, helpText, updateKey, "text", nextQuestion = nextQuestion.map(_.updateKey))
  override def answer = fromRsvp
  override def update(rsvp: Rsvp, choice: String): Rsvp = updateRsvp(rsvp, Some(choice))
  def next(question: QuestionReference): Text = this.copy(nextQuestion = Some(question))
  override def allOnwardQuestions: List[QuestionReference] = nextQuestion.toList
}

case class JsonAnswer(updateKey: String, text: String, nextQuestion: Option[String], colour: Option[String])
object JsonAnswer {
  implicit val jsonAnswerFormats = Json.format[JsonAnswer]
}
case class JsonQuestion(question: String, helpText: Option[String], updateKey: String, questionType: String, answers: List[JsonAnswer] = Nil, nextQuestion: Option[String] = None)
object JsonQuestion {
  implicit val jsonQuestionFormats = Json.format[JsonQuestion]
}

object Questions {
  lazy val startPage = areYouComing
  lazy val areYouComing: Question[Boolean] = MultipleChoice("Can you make Kith & Kin?", "canYouMakeIt", List(
    Answer("yes", "Yes!!")(true).next(everyoneComing),
    Answer("no", "Sadly not")(false).next(cannaeCome)
  ), updateRsvp = (rsvp, answer) => rsvp.modify(_.coming).setTo(answer), fromRsvp = _.coming)

  lazy val cannaeCome = Text("Send a message to Simon & Christina", "cannaeCome", updateRsvp = (rsvp, message) => rsvp.modify(_.message).setTo(message), fromRsvp = _.message)

  lazy val everyoneComing: Question[Boolean] = MultipleChoice("We're assuming that all of you are coming along, is that correct?", "everyoneComing", List(
    Answer("yes", "Yes, the whole clan")(true).next(accommodation),
    Answer("no", "Some of the clan cannae make it")(false)//.next(???) // TODO: some way of collecting who is and isn't coming
  ), updateRsvp = (rsvp, answer) => rsvp.modify(_.everyone).setTo(answer), fromRsvp = _.everyone)
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
  ), updateRsvp = (rsvp, answer) => rsvp.modify(_.bellTentSharing).setTo(answer), fromRsvp = _.bellTentSharing,
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

  lazy val message = Text("Send a message to Simon & Christina", "message", updateRsvp = (rsvp, message) => rsvp.modify(_.message).setTo(message), fromRsvp = _.message,
    helpText = Some("e.g. who you want to share tents with, more precise arrival and departure times or just a wee note saying how excited you are... "))

  val allQuestions: Seq[QuestionReference] = {
    def rec(question: QuestionReference): Set[QuestionReference] =
      Set(question) ++ question.allOnwardQuestions.flatMap(rec)
    val allQuestions = rec(startPage)
    assert(allQuestions.size == allQuestions.map(_.updateKey).size, "Something wrong with keys, a question update key has probably been used twice")
    allQuestions.toSeq
  }
  val jsonQuestions = allQuestions.map(_.jsonQuestion)
  val questionMap = jsonQuestions.map(q => q.updateKey -> q).toMap
  val questionJson = Json.obj(
    "questions" -> questionMap,
    "startPage" -> startPage.updateKey
  )

  def answers(rsvp: Rsvp): Map[String, String] = allQuestions.flatMap(q => q.answer(rsvp).map(q.updateKey ->)).toMap
}