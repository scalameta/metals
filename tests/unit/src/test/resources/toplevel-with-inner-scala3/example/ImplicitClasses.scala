package example

object ImplicitClasses/*example.ImplicitClasses.*/ {
  implicit class Xtension/*example.ImplicitClasses.Xtension#*/(number: Int) {
    def increment: Int = number + 1
  }
  implicit class XtensionAnyVal/*example.ImplicitClasses.XtensionAnyVal#*/(private val number: Int) extends AnyVal {
    def double: Int = number * 2
  }
}
