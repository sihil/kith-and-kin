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
case class Question[T](question: String, updateKey: String, answers: List[Answer[T]], updateInvite: (Invite, Option[Answer[T]]) => Invite, fromInvite: Invite => Option[T]) extends QuestionReference {
  def fromKey(key: String): Option[Answer[T]] = answers.find(_.updateKey == key)
  def update(invite: Invite, choice: String) = updateInvite(invite, fromKey(choice))
  def jsonQuestion = JsonQuestion(question, updateKey, answers.map(_.jsonAnswer))
  def answer(invite: Invite): Option[String] = {
    for {
      answerValue <- fromInvite(invite)
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
  val ensureRsvp = (invite: Invite) => invite.modify(_.rsvp).using(_.orElse(Some(Rsvp(coming = None))))

  lazy val startPage = areYouComing
  lazy val areYouComing: Question[Boolean] = Question("Can you make Kith & Kin?", "canYouMakeIt", List(
    Answer("yes", "Yes!!")(true).next(accommodation),
    Answer("no", "Sadly not")(false)
  ), (invite, answer) => ensureRsvp(invite).modify(_.rsvp.each.coming).setToIfDefined(Some(answer.map(_.internalValue))), _.rsvp.flatMap(_.coming))

  lazy val accommodation: Question[Accommodation] = Question("Where are you planning to stay?", "accommodation", List(
    Answer("ownTent", "Own tent")(OnSiteOwnTent),
    Answer("camper", "Own Campervan")(OnSiteOwnCamper),
    Answer("caravan", "Own Caravan")(OnSiteOwnCaravan),
    Answer("belltent", "Bell Tent")(OnSiteBellTent),
    Answer[Accommodation]("offsite", "Off Site")(OffSite).next(offsite)
  ), (invite, answer) => ensureRsvp(invite).modify(_.rsvp.each.accommodation).setToIfDefined(answer.map(a => Some(a.internalValue))),
    _.rsvp.flatMap(_.accommodation))

  lazy val offsite: Question[Boolean] = Question("Do you want to join us for breakfast on site?", "onSiteBreakfast", List(
    Answer("yes", "Yes")(true),
    Answer("no", "No, thanks")(false)
  ), (invite, answer) => ensureRsvp(invite).modify(_.rsvp.each.offSiteHavingBreakfast).setToIfDefined(answer.map(a => Some(a.internalValue))),
    _.rsvp.flatMap(_.offSiteHavingBreakfast))

  val allQuestions: Seq[Question[_]] = Seq(areYouComing, accommodation, offsite)
  val jsonQuestions = allQuestions.map(_.jsonQuestion)
  val questionMap = jsonQuestions.map(q => q.updateKey -> q).toMap
  val questionJson = Json.obj(
    "questions" -> questionMap,
    "startPage" -> startPage.updateKey
  )

  def answers(invite: Invite): Map[String, String] = allQuestions.flatMap(q => q.answer(invite).map(q.updateKey ->)).toMap
}