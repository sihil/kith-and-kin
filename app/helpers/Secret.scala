package helpers

import scala.util.Random

object Secret {
  val random = new Random()

  def randomChar: Char = {
    val char = Math.abs(random.nextInt) % 62
    char match {
      case lower if lower < 26 => ('a' + lower).toChar
      case upper if upper >= 26 && upper < 52 => ('A' + (upper - 26)).toChar
      case numeral if numeral >= 52 && numeral < 62 => ('0' + (numeral - 52)).toChar
      case default =>
        throw new IllegalStateException("value out of expected range")
    }
  }

  def newSecret(length: Int = 32): String = {
    (1 until length).map(_ => randomChar).mkString
  }
}
