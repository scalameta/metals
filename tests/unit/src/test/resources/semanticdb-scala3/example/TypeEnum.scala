   package example
//         ^^^^^^^ definition example/

   enum TypeEnum:
//      ^^^^^^^^ definition example/TypeEnum#
     case Product(val id: String)
//   ^ definition example/TypeEnum#`<init>`().
//        ^^^^^^^ definition example/TypeEnum.Product#
//               ^ definition example/TypeEnum.Product#`<init>`().
//                    ^^ definition example/TypeEnum.Product#id.
//                        ^^^^^^ reference scala/Predef.String#
     case Coproduct()
//        ^^^^^^^^^ definition example/TypeEnum.Coproduct#
//                 ^ definition example/TypeEnum.Coproduct#`<init>`().
