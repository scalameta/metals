   package example.nested
//         ^^^^^^^ reference example/
//                 ^^^^^^ definition example/nested/

   trait LocalDeclarations:
//       ^^^^^^^^^^^^^^^^^ definition example/nested/LocalDeclarations#
     def foo(): Unit
//   ^ definition example/nested/LocalDeclarations#`<init>`().
//       ^^^ definition example/nested/LocalDeclarations#foo().
//              ^^^^ reference scala/Unit#

   trait Foo:
//       ^^^ definition example/nested/Foo#
     val y = 3
//   ^ definition example/nested/Foo#`<init>`().
//       ^ definition example/nested/Foo#y.

   object LocalDeclarations:
//        ^^^^^^^^^^^^^^^^^ definition example/nested/LocalDeclarations.
     def create(): LocalDeclarations =
//       ^^^^^^ definition example/nested/LocalDeclarations.create().
//                 ^^^^^^^^^^^^^^^^^ reference example/nested/LocalDeclarations#
       def bar(): Unit = ()
//         ^^^ definition local0
//                ^^^^ reference scala/Unit#

       val x = new:
//         ^ definition local4
         val x = 2
//       ^ definition local2
//           ^ definition local1

       val y = new Foo {}
//         ^ definition local7
//             ^ definition local5
//                 ^^^ reference example/nested/Foo#

       val yy = y.y
//         ^^ definition local8
//              ^ reference local7
//                ^ reference example/nested/Foo#y.

       new LocalDeclarations with Foo:
//         ^ definition local10
//         ^^^^^^^^^^^^^^^^^ reference example/nested/LocalDeclarations#
//                                ^^^ reference example/nested/Foo#
         override def foo(): Unit = bar()
//                    ^^^ definition local9
//                           ^^^^ reference scala/Unit#
//                                  ^^^ reference local0
