package example

// This wasn't possible in Scala 2
implicit class Xtension/*example.ToplevelImplicit$package.Xtension#*/(number/*example.ToplevelImplicit$package.Xtension#number.*/: Int/*scala.Int#*/) {
    def increment/*example.ToplevelImplicit$package.Xtension#increment().*/: Int/*scala.Int#*/ = number/*example.ToplevelImplicit$package.Xtension#number.*/ +/*scala.Int#`+`(+4).*/ 1
}

implicit class XtensionAnyVal/*example.ToplevelImplicit$package.XtensionAnyVal#*/(private val number/*example.ToplevelImplicit$package.XtensionAnyVal#number.*/: Int/*scala.Int#*/) extends AnyVal/*scala.AnyVal#*/ {
    def double/*example.ToplevelImplicit$package.XtensionAnyVal#double().*/: Int/*scala.Int#*/ = number/*example.ToplevelImplicit$package.XtensionAnyVal#number.*/ */*scala.Int#`*`(+3).*/ 2
}
