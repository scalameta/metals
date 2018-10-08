package example

object ImplicitClasses/*ImplicitClasses.semanticdb*/ {
  implicit class Xtension/*ImplicitClasses.semanticdb*/(number/*ImplicitClasses.semanticdb*/: Int/*Int.scala*/) {
    def increment/*ImplicitClasses.semanticdb*/: Int/*Int.scala*/ = number/*ImplicitClasses.semanticdb*/ +/*Int.scala*/ 1
  }
  implicit class XtensionAnyVal/*ImplicitClasses.semanticdb*/(private val number/*ImplicitClasses.semanticdb*/: Int/*Int.scala*/) extends AnyVal/*AnyVal.scala*/ {
    def double/*ImplicitClasses.semanticdb*/: Int/*Int.scala*/ = number/*ImplicitClasses.semanticdb*/ */*Int.scala*/ 2
  }
}
