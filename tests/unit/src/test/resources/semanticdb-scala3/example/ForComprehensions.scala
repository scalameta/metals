   package example
//         ^^^^^^^ definition example/

   class ForComprehensions {
//       ^^^^^^^^^^^^^^^^^ definition example/ForComprehensions#
     for {
//   ^ definition example/ForComprehensions#`<init>`().
       a <- List(1)
//     ^ definition local0
//          ^^^^ reference scala/package.List.
       b <- List(a)
//     ^ definition local1
//          ^^^^ reference scala/package.List.
//               ^ reference local0
       if (
         a,
//       ^ reference local0
         b,
//       ^ reference local1
       ) == (1, 2)
//       ^^ reference scala/Any#`==`().
       (
         c,
//       ^ definition local3
         d,
//       ^ definition local4
       ) <- List((a, b))
//          ^^^^ reference scala/package.List.
//                ^ reference local0
//                   ^ reference local1
       if (
         a,
//       ^ reference local0
         b,
//       ^ reference local1
         c,
//       ^ reference local3
         d,
//       ^ reference local4
       ) == (1, 2, 3, 4)
//       ^^ reference scala/Any#`==`().
       e = (
//     ^ definition local5
//     ^ reference local5
         a,
//       ^ reference local0
         b,
//       ^ reference local1
         c,
//       ^ reference local3
         d,
//       ^ reference local4
       )
       if e == (1, 2, 3, 4)
//        ^ reference local5
//          ^^ reference scala/Any#`==`().
       f <- List(e)
//     ^ definition local6
//          ^^^^ reference scala/package.List.
//               ^ reference local5
     } yield {
       (
         a,
//       ^ reference local0
         b,
//       ^ reference local1
         c,
//       ^ reference local3
         d,
//       ^ reference local4
         e,
//       ^ reference local5
         f,
//       ^ reference local6
       )
     }

   }
