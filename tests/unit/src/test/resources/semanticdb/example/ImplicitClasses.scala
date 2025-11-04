   package example
//         ^^^^^^^ definition example/

   object ImplicitClasses {
//        ^^^^^^^^^^^^^^^ definition example/ImplicitClasses.
     implicit class Xtension(number: Int) {
//                  ^^^^^^^^ definition example/ImplicitClasses.Xtension#
//                          ^ definition example/ImplicitClasses.Xtension#`<init>`().
//                           ^^^^^^ definition example/ImplicitClasses.Xtension#number.
//                                   ^^^ reference scala/Int#
       def increment: Int = number + 1
//         ^^^^^^^^^ definition example/ImplicitClasses.Xtension#increment().
//                    ^^^ reference scala/Int#
//                          ^^^^^^ reference example/ImplicitClasses.Xtension#number.
//                                 ^ reference scala/Int#`+`(+4).
     }
     implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
//                  ^^^^^^^^^^^^^^ definition example/ImplicitClasses.XtensionAnyVal#
//                                ^ definition example/ImplicitClasses.XtensionAnyVal#`<init>`().
//                                             ^^^^^^ definition example/ImplicitClasses.XtensionAnyVal#number.
//                                                     ^^^ reference scala/Int#
//                                                                  ^^^^^^ reference scala/AnyVal#
//                                                                         ^ reference scala/AnyVal#`<init>`().
       def double: Int = number * 2
//         ^^^^^^ definition example/ImplicitClasses.XtensionAnyVal#double().
//                 ^^^ reference scala/Int#
//                       ^^^^^^ reference example/ImplicitClasses.XtensionAnyVal#number.
//                              ^ reference scala/Int#`*`(+3).
     }
   }
