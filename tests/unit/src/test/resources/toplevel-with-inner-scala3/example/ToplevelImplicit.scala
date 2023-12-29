package example

// This wasn't possible in Scala 2
implicit class/*example.ToplevelImplicit$package.*/ Xtension/*example.ToplevelImplicit$package.Xtension#*/(number: Int) {
    def increment: Int = number + 1
}

implicit class XtensionAnyVal/*example.ToplevelImplicit$package.XtensionAnyVal#*/(private val number: Int) extends AnyVal {
    def double: Int = number * 2
}
