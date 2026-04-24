   package example
//         ^^^^^^^ definition example/

   // This wasn't possible in Scala 2
   implicit class Xtension(number: Int) {
//                ^^^^^^^^ definition example/ToplevelImplicit$package.Xtension#
//                        ^ definition example/ToplevelImplicit$package.Xtension#`<init>`().
//                         ^^^^^^ definition example/ToplevelImplicit$package.Xtension#number.
//                                 ^^^ reference scala/Int#
       def increment: Int = number + 1
//         ^^^^^^^^^ definition example/ToplevelImplicit$package.Xtension#increment().
//                    ^^^ reference scala/Int#
//                          ^^^^^^ reference example/ToplevelImplicit$package.Xtension#number.
//                                 ^ reference scala/Int#`+`(+4).
   }

   implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
//                ^^^^^^^^^^^^^^ definition example/ToplevelImplicit$package.XtensionAnyVal#
//                              ^ definition example/ToplevelImplicit$package.XtensionAnyVal#`<init>`().
//                                           ^^^^^^ definition example/ToplevelImplicit$package.XtensionAnyVal#number.
//                                                   ^^^ reference scala/Int#
//                                                                ^^^^^^ reference scala/AnyVal#
       def double: Int = number * 2
//         ^^^^^^ definition example/ToplevelImplicit$package.XtensionAnyVal#double().
//                 ^^^ reference scala/Int#
//                       ^^^^^^ reference example/ToplevelImplicit$package.XtensionAnyVal#number.
//                              ^ reference scala/Int#`*`(+3).
   }
