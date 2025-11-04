   package example
//         ^^^^^^^ definition example/

   import io.circe.derivation.deriveDecoder
//        ^^ reference io/
//           ^^^^^ reference io/circe/
//                 ^^^^^^^^^^ reference io/circe/derivation/
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveDecoder(+2).
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveDecoder(+1).
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveDecoder().
   import io.circe.derivation.deriveEncoder
//        ^^ reference io/
//           ^^^^^ reference io/circe/
//                 ^^^^^^^^^^ reference io/circe/derivation/
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveEncoder(+2).
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveEncoder(+1).
//                            ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveEncoder().

   class Definitions {
//       ^^^^^^^^^^^ definition example/Definitions#
//                   ^ definition example/Definitions#`<init>`().
     Predef.any2stringadd(1)
//   ^^^^^^ reference scala/Predef.
//          ^^^^^^^^^^^^^ reference scala/Predef.any2stringadd().
     List[
//   ^^^^ reference scala/package.List.
       java.util.Map.Entry[
//     ^^^^ reference java/
//          ^^^^ reference java/util/
//               ^^^ reference java/util/Map#
//                   ^^^^^ reference java/util/Map#Entry#
         java.lang.Integer,
//       ^^^^ reference java/
//            ^^^^ reference java/lang/
//                 ^^^^^^^ reference java/lang/Integer#
         java.lang.Double,
//       ^^^^ reference java/
//            ^^^^ reference java/lang/
//                 ^^^^^^ reference java/lang/Double#
       ]
     ](
       elems = null
//     ^^^^^ reference scala/collection/IterableFactory#apply().(elems)
     )
     println(deriveDecoder[MacroAnnotation])
//   ^^^^^^^ reference scala/Predef.println(+1).
//           ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveDecoder().
//                        ^ definition local0
//                         ^^^^^^^^^^^^^^^ reference example/MacroAnnotation#
     println(deriveEncoder[MacroAnnotation])
//   ^^^^^^^ reference scala/Predef.println(+1).
//           ^^^^^^^^^^^^^ reference io/circe/derivation/package.deriveEncoder().
//                        ^ definition local14
//                         ^^^^^^^^^^^^^^^ reference example/MacroAnnotation#
   }
