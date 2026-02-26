   package example
//         ^^^^^^^ definition example/

   class PatternMatching {
//       ^^^^^^^^^^^^^^^ definition example/PatternMatching#
//                       ^ definition example/PatternMatching#`<init>`().
     val some = Some(1)
//       ^^^^ definition example/PatternMatching#some.
//              ^^^^ reference scala/Some.
     some match {
//   ^^^^ reference example/PatternMatching#some.
       case Some(number) =>
//          ^^^^ reference scala/Some.
//               ^^^^^^ definition local0
         number
//       ^^^^^^ reference local0
     }

     // tuple deconstruction
     val (left, right) = (1, 2)
//        ^^^^ definition example/PatternMatching#left.
//              ^^^^^ definition example/PatternMatching#right.
     (left, right)
//    ^^^^ reference example/PatternMatching#left.
//          ^^^^^ reference example/PatternMatching#right.

     // val deconstruction
     val Some(number1) =
//       ^^^^ reference scala/Some.
//            ^^^^^^^ definition example/PatternMatching#number1.
       some
//     ^^^^ reference example/PatternMatching#some.
     println(number1)
//   ^^^^^^^ reference scala/Predef.println(+1).
//           ^^^^^^^ reference example/PatternMatching#number1.

     def localDeconstruction = {
//       ^^^^^^^^^^^^^^^^^^^ definition example/PatternMatching#localDeconstruction().
       val Some(number2) =
//         ^^^^ reference scala/Some.
//              ^^^^^^^ definition local4
         some
//       ^^^^ reference example/PatternMatching#some.
       number2
//     ^^^^^^^ reference local4
     }
   }
