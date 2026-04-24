   package example
//         ^^^^^^^ definition example/

   trait Ord[T]:
//       ^^^ definition example/Ord#
//          ^ definition example/Ord#`<init>`().
//           ^ definition example/Ord#[T]
     def compare(x: T, y: T): Int
//       ^^^^^^^ definition example/Ord#compare().
//               ^ definition example/Ord#compare().(x)
//                  ^ reference example/Ord#[T]
//                     ^ definition example/Ord#compare().(y)
//                        ^ reference example/Ord#[T]
//                            ^^^ reference scala/Int#

   given intOrd: Ord[Int] with
//       ^^^^^^ definition example/Ord$package.intOrd.
//               ^^^ reference example/Ord#
//                   ^^^ reference scala/Int#
     def compare(x: Int, y: Int) =
//       ^^^^^^^ definition example/Ord$package.intOrd.compare().
//               ^ definition example/Ord$package.intOrd.compare().(x)
//                  ^^^ reference scala/Int#
//                       ^ definition example/Ord$package.intOrd.compare().(y)
//                          ^^^ reference scala/Int#
       if x < y then -1 else if x > y then +1 else 0
//        ^ reference example/Ord$package.intOrd.compare().(x)
//          ^ reference scala/Int#`<`(+3).
//            ^ reference example/Ord$package.intOrd.compare().(y)
//                              ^ reference example/Ord$package.intOrd.compare().(x)
//                                ^ reference scala/Int#`>`(+3).
//                                  ^ reference example/Ord$package.intOrd.compare().(y)

   given Ord[String] with
//       ^^^ reference example/Ord#
//           ^^^^^^ reference scala/Predef.String#
     def compare(x: String, y: String) =
//       ^^^^^^^ definition example/Ord$package.given_Ord_String.compare().
//               ^ definition example/Ord$package.given_Ord_String.compare().(x)
//                  ^^^^^^ reference scala/Predef.String#
//                          ^ definition example/Ord$package.given_Ord_String.compare().(y)
//                             ^^^^^^ reference scala/Predef.String#
       x.compare(y)
//     ^ reference example/Ord$package.given_Ord_String.compare().(x)
//       ^^^^^^^ reference scala/collection/StringOps#compare().
//               ^ reference example/Ord$package.given_Ord_String.compare().(y)
