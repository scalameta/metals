   package example
//         ^^^^^^^ definition example/

   import util.{Failure => NotGood}
//        ^^^^ reference scala/util/
//              ^^^^^^^ reference scala/util/Failure.
//              ^^^^^^^ reference scala/util/Failure#
   import math.{floor => _, _}
//        ^^^^ reference scala/math/
//              ^^^^^ reference scala/math/package.floor().

   class Imports {
//       ^^^^^^^ definition example/Imports#
     // rename reference
     NotGood(null)
//   ^ definition example/Imports#`<init>`().
//   ^^^^^^^ reference scala/util/Failure.
     max(1, 2)
//   ^^^ reference scala/math/package.max().
   }
