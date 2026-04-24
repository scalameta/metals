package example

// This wasn't possible in Scala 2
implicit class Xtension/*example.Xtension().*//*example.Xtension#*/(number/*example.Xtension#number.*/: Int) {
    def increment/*example.Xtension#increment().*/: Int = number + 1
}

implicit class XtensionAnyVal/*example.XtensionAnyVal().*//*example.XtensionAnyVal#*/(private val number/*example.XtensionAnyVal#number.*/: Int) extends AnyVal {
    def double/*example.XtensionAnyVal#double().*/: Int = number * 2
}
