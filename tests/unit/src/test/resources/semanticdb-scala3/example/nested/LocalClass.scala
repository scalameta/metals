   package example.nested
//         ^^^^^^^ reference example/
//                 ^^^^^^ definition example/nested/

   class LocalClass {
//       ^^^^^^^^^^ definition example/nested/LocalClass#
     def foo(): Unit = {
//   ^ definition example/nested/LocalClass#`<init>`().
//       ^^^ definition example/nested/LocalClass#foo().
//              ^^^^ reference scala/Unit#
       case class LocalClass()
//                ^^^^^^^^^^ definition local1
     }
   }
