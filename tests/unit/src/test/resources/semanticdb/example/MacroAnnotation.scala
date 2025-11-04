   package example
//         ^^^^^^^ definition example/

   import io.circe.derivation.annotations.JsonCodec
//        ^^ reference io/
//           ^^^^^ reference io/circe/
//                 ^^^^^^^^^^ reference io/circe/derivation/
//                            ^^^^^^^^^^^ reference io/circe/derivation/annotations/
//                                        ^^^^^^^^^ reference io/circe/derivation/annotations/JsonCodec.
//                                        ^^^^^^^^^ reference io/circe/derivation/annotations/JsonCodec#

   @JsonCodec
//  ^ definition local1
//  ^^^^^^^^^ reference example/MacroAnnotation#
//           ^ reference java/lang/Object#`<init>`().
   // FIXME: https://github.com/scalameta/scalameta/issues/1789
   case class MacroAnnotation(
       name: String
//     ^^^^ definition example/MacroAnnotation#name.
//           ^^^^^^ reference scala/Predef.String#
   ) {
     def method = 42
//       ^^^^^^ definition example/MacroAnnotation#method().
   }

   object MacroAnnotations {
//        ^^^^^^^^^^^^^^^^ definition example/MacroAnnotations.
     import scala.meta._
//          ^^^^^ reference scala/
//                ^^^^ reference scala/meta/
     // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
     // due to the macro annotations.
     val x: Defn.Class = Defn.Class(
//       ^ definition example/MacroAnnotations.x.
//          ^^^^ reference scala/meta/Defn.
//               ^^^^^ reference scala/meta/Defn.Class#
//                       ^^^^ reference scala/meta/Defn.
//                            ^^^^^ reference scala/meta/Defn.Class.
       Nil,
//     ^^^ reference scala/package.Nil.
       Type.Name("test"),
//     ^^^^ reference scala/meta/Type.
//          ^^^^ reference scala/meta/Type.Name.
       Nil,
//     ^^^ reference scala/package.Nil.
       Ctor.Primary(Nil, Term.Name("this"), Nil),
//     ^^^^ reference scala/meta/Ctor.
//          ^^^^^^^ reference scala/meta/Ctor.Primary.
//                  ^^^ reference scala/package.Nil.
//                       ^^^^ reference scala/meta/Term.
//                            ^^^^ reference scala/meta/Term.Name.
//                                          ^^^ reference scala/package.Nil.
       Template(Nil, Nil, Self(Name.Anonymous(), None), Nil),
//     ^^^^^^^^ reference scala/meta/Template.
//              ^^^ reference scala/package.Nil.
//                   ^^^ reference scala/package.Nil.
//                        ^^^^ reference scala/meta/Self.
//                             ^^^^ reference scala/meta/Name.
//                                  ^^^^^^^^^ reference scala/meta/Name.Anonymous.
//                                               ^^^^ reference scala/None.
//                                                      ^^^ reference scala/package.Nil.
     )
     val y: Mod.Final = Mod.Final()
//       ^ definition example/MacroAnnotations.y.
//          ^^^ reference scala/meta/Mod.
//              ^^^^^ reference scala/meta/Mod.Final#
//                      ^^^ reference scala/meta/Mod.
//                          ^^^^^ reference scala/meta/Mod.Final.
   }
