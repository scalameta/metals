   package example
//         ^^^^^^^ definition example/

   class Scalalib {
//       ^^^^^^^^ definition example/Scalalib#
//                ^ definition example/Scalalib#`<init>`().
     val nil = List()
//       ^^^ definition example/Scalalib#nil.
//             ^^^^ reference scala/collection/immutable/Nil.
     val lst = List[
//       ^^^ definition example/Scalalib#lst.
//             ^^^^ reference scala/package.List.
       (
           Nothing,
//         ^^^^^^^ reference scala/Nothing#
           Null,
//         ^^^^ reference scala/Null#
           Singleton,
//         ^^^^^^^^^ reference scala/Singleton#
           Any,
//         ^^^ reference scala/Any#
           AnyRef,
//         ^^^^^^ reference scala/AnyRef#
           AnyVal,
//         ^^^^^^ reference scala/AnyVal#
           Int,
//         ^^^ reference scala/Int#
           Short,
//         ^^^^^ reference scala/Short#
           Double,
//         ^^^^^^ reference scala/Double#
           Float,
//         ^^^^^ reference scala/Float#
           Char,
//         ^^^^ reference scala/Char#
       )
     ](null)
     lst.isInstanceOf[Any]
//   ^^^ reference example/Scalalib#lst.
//       ^^^^^^^^^^^^ reference scala/Any#isInstanceOf().
//                    ^^^ reference scala/Any#
     lst.asInstanceOf[Any]
//   ^^^ reference example/Scalalib#lst.
//       ^^^^^^^^^^^^ reference scala/Any#asInstanceOf().
//                    ^^^ reference scala/Any#
     println(lst.##)
//   ^^^^^^^ reference scala/Predef.println(+1).
//           ^^^ reference example/Scalalib#lst.
//               ^^ reference java/lang/Object#`##`().
     lst ne lst
//   ^^^ reference example/Scalalib#lst.
//       ^^ reference java/lang/Object#ne().
//          ^^^ reference example/Scalalib#lst.
     lst eq lst
//   ^^^ reference example/Scalalib#lst.
//       ^^ reference java/lang/Object#eq().
//          ^^^ reference example/Scalalib#lst.
     lst == lst
//   ^^^ reference example/Scalalib#lst.
//       ^^ reference java/lang/Object#`==`().
//          ^^^ reference example/Scalalib#lst.
   }
