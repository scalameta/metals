   package example
//         ^^^^^^^ definition example/

   trait Cancelable
// ^ definition example/Cancelable#`<init>`().
//       ^^^^^^^^^^ definition example/Cancelable#
   trait Movable
// ^ definition example/Movable#`<init>`().
//       ^^^^^^^ definition example/Movable#

   type Y = (Cancelable & Movable)
//      ^ definition example/AndOrType$package.Y#
//           ^^^^^^^^^^ reference example/Cancelable#
//                      ^ reference scala/`&`#
//                        ^^^^^^^ reference example/Movable#

   type X = String | Int
//      ^ definition example/AndOrType$package.X#
//          ^^^^^^ reference scala/Predef.String#
//                 ^ reference scala/`|`#
//                   ^^^ reference scala/Int#
