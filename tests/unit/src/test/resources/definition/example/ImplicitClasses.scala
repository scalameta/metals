package example

object ImplicitClasses/*ImplicitClasses.scala*/ {
  implicit class Xtension/*ImplicitClasses.scala*/(number/*ImplicitClasses.scala*/: Int/*Int.scala*/) {
    def increment/*ImplicitClasses.scala*/: Int/*Int.scala*/ = number/*ImplicitClasses.scala*/ +/*Int.scala*/ 1
  }
  implicit class XtensionAnyVal/*ImplicitClasses.scala*/(private val number/*ImplicitClasses.scala*/: Int/*Int.scala*/) extends AnyVal/*AnyVal.scala*/ {
    def double/*ImplicitClasses.scala*/: Int/*Int.scala*/ = number/*ImplicitClasses.scala*/ */*Int.scala*/ 2
  }
}
