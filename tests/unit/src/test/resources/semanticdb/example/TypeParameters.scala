   package example
//         ^^^^^^^ definition example/

   class TypeParameters[A] {
//       ^^^^^^^^^^^^^^ definition example/TypeParameters#
//                      ^ definition example/TypeParameters#[A]
//                         ^ definition example/TypeParameters#`<init>`().
     def method[B] = 42
//       ^^^^^^ definition example/TypeParameters#method().
//              ^ definition example/TypeParameters#method().[B]
     trait TraitParameter[C]
//         ^^^^^^^^^^^^^^ definition example/TypeParameters#TraitParameter#
//                        ^ definition example/TypeParameters#TraitParameter#[C]
     type AbstractTypeAlias[D]
//        ^^^^^^^^^^^^^^^^^ definition example/TypeParameters#AbstractTypeAlias#
//                          ^ definition example/TypeParameters#AbstractTypeAlias#[D]
     type TypeAlias[E] = List[E]
//        ^^^^^^^^^ definition example/TypeParameters#TypeAlias#
//                  ^ definition example/TypeParameters#TypeAlias#[E]
//                       ^^^^ reference scala/package.List#
//                            ^ reference example/TypeParameters#TypeAlias#[E]
   }
