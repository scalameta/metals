   package example.nested
//         ^^^^^^^ reference example/
//                 ^^^^^^ reference example/nested/

   trait LocalDeclarations {
//       ^^^^^^^^^^^^^^^^^ definition example/nested/LocalDeclarations#
     def foo(): Unit
//       ^^^ definition example/nested/LocalDeclarations#foo().
//              ^^^^ reference scala/Unit#
   }

   trait Foo {
//       ^^^ definition example/nested/Foo#
     val y = 3
//       ^ definition example/nested/Foo#y.
   }

   object LocalDeclarations {
//        ^^^^^^^^^^^^^^^^^ definition example/nested/LocalDeclarations.
     def create(): LocalDeclarations = {
//       ^^^^^^ definition example/nested/LocalDeclarations.create().
//                 ^^^^^^^^^^^^^^^^^ reference example/nested/LocalDeclarations#
       def bar(): Unit = ()
//         ^^^ definition local0
//                ^^^^ reference scala/Unit#

       val x = new {
//         ^ definition local3
//                 ^ definition local1
         val x = 2
//           ^ definition local2
       }

       val y = new Foo {}
//         ^ definition local5
//                 ^ definition local4
//                 ^^^ reference example/nested/Foo#
//                     ^ reference java/lang/Object#`<init>`().

       x.x + y.y
//     ^ reference local3
//       ^ reference local2
//         ^ reference scala/Int#`+`(+4).
//           ^ reference local5
//             ^ reference example/nested/Foo#y.

       new LocalDeclarations with Foo {
//         ^ definition local6
//         ^^^^^^^^^^^^^^^^^ reference example/nested/LocalDeclarations#
//                           ^ reference java/lang/Object#`<init>`().
//                                ^^^ reference example/nested/Foo#
         override def foo(): Unit = bar()
//                    ^^^ definition local7
//                           ^^^^ reference scala/Unit#
//                                  ^^^ reference local0
       }

     }
   }
