   package example
//         ^^^^^^^ definition example/

   abstract class Companion() extends Object() {}
//                ^^^^^^^^^ definition example/Companion#
//                         ^ definition example/Companion#`<init>`().
//                                    ^^^^^^ reference java/lang/Object#
//                                          ^ reference java/lang/Object#`<init>`().

   object Companion {}
//        ^^^^^^^^^ definition example/Companion.
