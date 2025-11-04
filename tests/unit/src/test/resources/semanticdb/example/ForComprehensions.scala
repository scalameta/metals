   package example
//         ^^^^^^^ definition example/

   class ForComprehensions {
//       ^^^^^^^^^^^^^^^^^ definition example/ForComprehensions#
//                         ^ definition example/ForComprehensions#`<init>`().
     for {
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
//       ^^ reference java/lang/Object#`==`().
       (
         c,
//       ^ definition local7
         d,
//       ^ definition local8
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
//       ^ reference local7
         d,
//       ^ reference local8
       ) == (1, 2, 3, 4)
//       ^^ reference java/lang/Object#`==`().
       e = (
//     ^ definition local10
         a,
//       ^ reference local0
         b,
//       ^ reference local1
         c,
//       ^ reference local7
         d,
//       ^ reference local8
       )
       if e == (1, 2, 3, 4)
//        ^ reference local10
//          ^^ reference java/lang/Object#`==`().
       f <- List(e)
//     ^ definition local11
//          ^^^^ reference scala/package.List.
//               ^ reference local10
     } yield {
       (
         a,
//       ^ reference local0
         b,
//       ^ reference local1
         c,
//       ^ reference local7
         d,
//       ^ reference local8
         e,
//       ^ reference local10
         f,
//       ^ reference local11
       )
     }

   }
