package example

// This wasn't possible in Scala 2
implicit class Xtension/*<no file>*/(number/*<no file>*/: Int/*Int.scala*/) {
    def increment/*<no file>*/: Int/*Int.scala*/ = number/*<no file>*/ +/*Int.scala*/ 1
}

implicit class XtensionAnyVal/*<no file>*/(private val number/*<no file>*/: Int/*Int.scala*/) extends AnyVal/*AnyVal.scala*/ {
    def double/*<no file>*/: Int/*Int.scala*/ = number/*<no file>*/ */*Int.scala*/ 2
}
