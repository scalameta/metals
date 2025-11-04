   package example
//         ^^^^^^^ definition example/

   class MethodOverload(b: String) {
//       ^^^^^^^^^^^^^^ definition example/MethodOverload#
//                     ^ definition example/MethodOverload#`<init>`().
//                      ^ definition example/MethodOverload#b.
//                         ^^^^^^ reference scala/Predef.String#
     def this() = this("")
//       ^^^^ definition example/MethodOverload#`<init>`(+1).
//                    ^ reference example/MethodOverload#`<init>`().
     def this(c: Int) = this("")
//       ^^^^ definition example/MethodOverload#`<init>`(+2).
//            ^ definition example/MethodOverload#`<init>`(+2).(c)
//               ^^^ reference scala/Int#
//                          ^ reference example/MethodOverload#`<init>`().
     val a = 2
//       ^ definition example/MethodOverload#a.
     def a(x: Int) = 2
//       ^ definition example/MethodOverload#a().
//         ^ definition example/MethodOverload#a().(x)
//            ^^^ reference scala/Int#
     def a(x: Int, y: Int) = 2
//       ^ definition example/MethodOverload#a(+1).
//         ^ definition example/MethodOverload#a(+1).(x)
//            ^^^ reference scala/Int#
//                 ^ definition example/MethodOverload#a(+1).(y)
//                    ^^^ reference scala/Int#
   }
