package object helpers {
  val currency = "£"
  implicit class IntCurrency(val int: Int) extends AnyVal {
    def sterling: String = {
      val sign = if (int < 0) "-" else ""
      val initialString = math.abs(int).toString
      val minimalString = if (initialString.length < 3) "0" * (3-initialString.length) + initialString else initialString
      val (pounds, pence) = minimalString.splitAt(minimalString.length-2)
      s"$sign$currency$pounds.$pence"
    }
  }
}
