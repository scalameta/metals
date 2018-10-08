package example

object ImplicitClasses/*example.ImplicitClasses.*/ {
  implicit class Xtension/*example.ImplicitClasses.Xtension#*/(number/*example.ImplicitClasses.Xtension#number.*/: Int/*scala.Int#*/) {
    def increment/*example.ImplicitClasses.Xtension#increment().*/: Int/*scala.Int#*/ = number/*example.ImplicitClasses.Xtension#number.*/ +/*scala.Int#`+`(+4).*/ 1
  }
  implicit class XtensionAnyVal/*example.ImplicitClasses.XtensionAnyVal#*/(private val number/*example.ImplicitClasses.XtensionAnyVal#number.*/: Int/*scala.Int#*/) extends AnyVal/*scala.AnyVal#*/ /*scala.AnyVal#`<init>`().*/{
    def double/*example.ImplicitClasses.XtensionAnyVal#double().*/: Int/*scala.Int#*/ = number/*example.ImplicitClasses.XtensionAnyVal#number.*/ */*scala.Int#`*`(+3).*/ 2
  }
}
