   package example
//         ^^^^^^^ definition example/

   // inline keyword
   inline def inlineMethod(x: Int): Int = x + 1
//            ^^^^^^^^^^^^ definition example/SoftKeywords$package.inlineMethod().
//                         ^ definition example/SoftKeywords$package.inlineMethod().(x)
//                            ^^^ reference scala/Int#
//                                  ^^^ reference scala/Int#
//                                        ^ reference example/SoftKeywords$package.inlineMethod().(x)
//                                          ^ reference scala/Int#`+`(+4).

   inline val inlineVal = 42
//            ^^^^^^^^^ definition example/SoftKeywords$package.inlineVal.

   // opaque type
   opaque type OpaqueInt = Int
//             ^^^^^^^^^ definition example/SoftKeywords$package.OpaqueInt#
//                         ^^^ reference scala/Int#

   // open class
   open class OpenClass
// ^ definition example/OpenClass#`<init>`().
//            ^^^^^^^^^ definition example/OpenClass#

   // transparent trait
   transparent trait TransparentTrait
// ^ definition example/TransparentTrait#`<init>`().
//                   ^^^^^^^^^^^^^^^^ definition example/TransparentTrait#

   // infix method
   class InfixExample:
//       ^^^^^^^^^^^^ definition example/InfixExample#
     infix def combine(other: InfixExample): InfixExample = this
//   ^ definition example/InfixExample#`<init>`().
//             ^^^^^^^ definition example/InfixExample#combine().
//                     ^^^^^ definition example/InfixExample#combine().(other)
//                            ^^^^^^^^^^^^ reference example/InfixExample#
//                                           ^^^^^^^^^^^^ reference example/InfixExample#

   // derives clause
   case class Point(x: Int, y: Int) derives CanEqual
//            ^^^^^ definition example/Point#
//                 ^ definition example/Point#`<init>`().
//                  ^ definition example/Point#x.
//                     ^^^ reference scala/Int#
//                          ^ definition example/Point#y.
//                             ^^^ reference scala/Int#

   // as in import rename
   import scala.collection.mutable.ListBuffer as MutableList
//        ^^^^^ reference scala/
//              ^^^^^^^^^^ reference scala/collection/
//                         ^^^^^^^ reference scala/collection/mutable/
//                                 ^^^^^^^^^^ reference scala/collection/mutable/ListBuffer.
//                                 ^^^^^^^^^^ reference scala/collection/mutable/ListBuffer#
