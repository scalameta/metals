package example

object ImplicitClasses {
  implicit class Xtension(number: Int) {
    def increment: Int = number + 1
  }
  implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    def double: Int = number * 2
  }
}
