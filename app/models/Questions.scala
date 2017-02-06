package models

import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsValue, Json, Writes}
import com.softwaremill.quicklens._

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
  def updateKey: String
}
case class Question[T](question: String, updateKey: String, answers: List[Answer[T]], updateRsvp: (Rsvp, Option[Answer[T]]) => Rsvp, fromRsvp: Rsvp => Option[T]) extends QuestionReference {
  def fromKey(key: String): Option[Answer[T]] = answers.find(_.updateKey == key)
  def update(rsvp: Rsvp, choice: String) = updateRsvp(rsvp, fromKey(choice))
  def jsonQuestion = JsonQuestion(question, updateKey, answers.map(_.jsonAnswer))
  def answer(rsvp: Rsvp): Option[String] = {
    for {
      answerValue <- fromRsvp(rsvp)
      answer <- answers.find(_.internalValue == answerValue)
    } yield { answer.updateKey }
  }
}

case class JsonAnswer(updateKey: String, text: String, nextQuestion: Option[String], colour: Option[String])
object JsonAnswer {
  implicit val jsonAnswerFormats = Json.format[JsonAnswer]
}
case class JsonQuestion(question: String, updateKey: String, answers: List[JsonAnswer])
object JsonQuestion {
  implicit val jsonQuestionFormats = Json.format[JsonQuestion]
}

object Questions {
  lazy val startPage = areYouComing
  lazy val areYouComing: Question[Boolean] = Question("Can you make Kith & Kin?", "canYouMakeIt", List(
    Answer("yes", "Yes!!")(true).next(accommodation),
    Answer("no", "Sadly not")(false)
  ), (rsvp, answer) => rsvp.modify(_.coming).setToIfDefined(Some(answer.map(_.internalValue))), _.coming)

  lazy val accommodation: Question[Accommodation] = Question("Where are you planning to stay?", "accommodation", List(
    Answer("ownTent", "Own tent")(OnSiteOwnTent),
    Answer("camper", "Own Campervan")(OnSiteOwnCamper),
    Answer("caravan", "Own Caravan")(OnSiteOwnCaravan),
    Answer("belltent", "Bell Tent")(OnSiteBellTent),
    Answer[Accommodation]("offsite", "Off Site")(OffSite).next(offsite)
  ), (rsvp, answer) => rsvp.modify(_.accommodation).setToIfDefined(answer.map(a => Some(a.internalValue))),
    _.accommodation)

  lazy val offsite: Question[Boolean] = Question("Do you want to join us for breakfast on site?", "onSiteBreakfast", List(
    Answer("yes", "Yes")(true),
    Answer("no", "No, thanks")(false)
  ), (rsvp, answer) => rsvp.modify(_.offSiteHavingBreakfast).setToIfDefined(answer.map(a => Some(a.internalValue))),
    _.offSiteHavingBreakfast)

  val allQuestions: Seq[Question[_]] = Seq(areYouComing, accommodation, offsite)
  val jsonQuestions = allQuestions.map(_.jsonQuestion)
  val questionMap = jsonQuestions.map(q => q.updateKey -> q).toMap
  val questionJson = Json.obj(
    "questions" -> questionMap,
    "startPage" -> startPage.updateKey
  )

  def answers(rsvp: Rsvp): Map[String, String] = allQuestions.flatMap(q => q.answer(rsvp).map(q.updateKey ->)).toMap
}