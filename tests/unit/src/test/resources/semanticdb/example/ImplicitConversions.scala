   package example
//         ^^^^^^^ definition example/

   class ImplicitConversions {
//       ^^^^^^^^^^^^^^^^^^^ definition example/ImplicitConversions#
//                           ^ definition example/ImplicitConversions#`<init>`().
     implicit def string2Number(
//                ^^^^^^^^^^^^^ definition example/ImplicitConversions#string2Number().
         string: String
//       ^^^^^^ definition example/ImplicitConversions#string2Number().(string)
//               ^^^^^^ reference scala/Predef.String#
     ): Int = 42
//      ^^^ reference scala/Int#
     val message = ""
//       ^^^^^^^ definition example/ImplicitConversions#message.
     val number = 42
//       ^^^^^^ definition example/ImplicitConversions#number.
     val tuple = (1, 2)
//       ^^^^^ definition example/ImplicitConversions#tuple.
     val char: Char = 'a'
//       ^^^^ definition example/ImplicitConversions#char.
//             ^^^^ reference scala/Char#

     // extension methods
     message
//   ^^^^^^^ reference example/ImplicitConversions#message.
       .stripSuffix("h")
//      ^^^^^^^^^^^ reference scala/collection/StringOps#stripSuffix().
     tuple + "Hello"
//   ^^^^^ reference example/ImplicitConversions#tuple.
//         ^ reference scala/Predef.any2stringadd#`+`().

     // implicit conversions
     val x: Int = message
//       ^ definition example/ImplicitConversions#x.
//          ^^^ reference scala/Int#
//                ^^^^^^^ reference example/ImplicitConversions#message.

     // interpolators
     s"Hello $message $number"
//   ^ reference scala/StringContext#s().
//            ^^^^^^^ reference example/ImplicitConversions#message.
//                     ^^^^^^ reference example/ImplicitConversions#number.
     s"""Hello
//   ^ reference scala/StringContext#s().
        |$message
//        ^^^^^^^ reference example/ImplicitConversions#message.
        |$number""".stripMargin
//        ^^^^^^ reference example/ImplicitConversions#number.
//                  ^^^^^^^^^^^ reference scala/collection/StringOps#stripMargin(+1).

     val a: Int = char
//       ^ definition example/ImplicitConversions#a.
//          ^^^ reference scala/Int#
//                ^^^^ reference example/ImplicitConversions#char.
     val b: Long = char
//       ^ definition example/ImplicitConversions#b.
//          ^^^^ reference scala/Long#
//                 ^^^^ reference example/ImplicitConversions#char.
   }
