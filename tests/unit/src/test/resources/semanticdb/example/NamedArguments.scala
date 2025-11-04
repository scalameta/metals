   package example
//         ^^^^^^^ definition example/

   case class User(
//            ^^^^ definition example/User#
//                ^ definition example/User#`<init>`().
       name: String = {
//     ^^^^ definition example/User#name.
//           ^^^^^^ reference scala/Predef.String#
         // assert default values have occurrences
         Map.toString
//       ^^^ reference scala/Predef.Map.
//           ^^^^^^^^ reference java/lang/Object#toString().
       }
   )
   object NamedArguments {
//        ^^^^^^^^^^^^^^ definition example/NamedArguments.
     final val susan = "Susan"
//             ^^^^^ definition example/NamedArguments.susan.
     val user1 =
//       ^^^^^ definition example/NamedArguments.user1.
       User
//     ^^^^ reference example/User.
         .apply(
//        ^^^^^ reference example/User.apply().
           name = "John"
//         ^^^^ reference example/User.apply().(name)
         )
     val user2: User =
//       ^^^^^ definition example/NamedArguments.user2.
//              ^^^^ reference example/User#
       User(
//     ^^^^ reference example/User.
         name = susan
//       ^^^^ reference example/User.apply().(name)
//              ^^^^^ reference example/NamedArguments.susan.
       ).copy(
//       ^^^^ reference example/User#copy().
         name = susan
//       ^^^^ reference example/User#copy().(name)
//              ^^^^^ reference example/NamedArguments.susan.
       )

     // anonymous classes
     @deprecated(
//    ^^^^^^^^^^ reference scala/deprecated#
//              ^ reference scala/deprecated#`<init>`().
       message = "a",
//     ^^^^^^^ reference scala/deprecated#`<init>`().(message)
       since = susan,
//     ^^^^^ reference scala/deprecated#`<init>`().(since)
//             ^^^^^ reference example/NamedArguments.susan.
     ) def b = 1
//         ^ definition example/NamedArguments.b().

     // vararg
     List(
//   ^^^^ reference scala/package.List.
       elems = 2
//     ^^^^^ reference scala/collection/IterableFactory#apply().(elems)
     )

   }
