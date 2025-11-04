   package example.nested
//         ^^^^^^^ reference example/
//                 ^^^^^^ reference example/nested/

   class LocalClass {
//       ^^^^^^^^^^ definition example/nested/LocalClass#
//                  ^ definition example/nested/LocalClass#`<init>`().
     def foo(): Unit = {
//       ^^^ definition example/nested/LocalClass#foo().
//              ^^^^ reference scala/Unit#
       case class LocalClass()
//                ^^^^^^^^^^ definition local0
//                          ^ definition local1
     }
   }
