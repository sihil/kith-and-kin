package models

import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsValue, Json, Writes}

sealed trait Item {
  def desc: String
  /** Amount in pennies */
  def amount: Int
  def english: Option[String] = None
}
case class Fixed(desc: String, amount: Int) extends Item
case class PerPerson(desc: String, amount: Int) extends Item {
  override def english: Option[String] = Some("per person")
}
case class PerAdult(desc: String, amount: Int) extends Item {
  override def english: Option[String] = Some("per adult")
}

object Item {
  implicit def writes(people: Int, adults: Int) = new Writes[Item] {
    override def writes(o: Item): JsValue = {
      val subTotal = Item.subTotal(o, people, adults)
      val fields: Seq[(String, JsValueWrapper)] = Seq("desc" -> o.desc, "amount" -> o.amount, "subTotal" -> subTotal)
      val english: Option[(String, JsValueWrapper)] = o.english.map(e => "english" -> e)
      Json.obj(fields ++ english: _*)
    }
  }
  def subTotal(price: Item, people: Int, adults: Int): Int = {
    price match {
      case Fixed(_, amount) => amount
      case PerPerson(_, amount) => people * amount
      case PerAdult(_, amount) => adults * amount
    }
  }
  def total(prices: List[Item], people: Int, adults: Int): Int = prices.map(p => subTotal(p, people, adults)).sum
}