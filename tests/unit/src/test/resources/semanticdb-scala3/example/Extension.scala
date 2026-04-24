   package example
//         ^^^^^^^ definition example/

   extension (i: Int) def asString: String = i.toString
//            ^ definition example/Extension$package.asString().(i)
//               ^^^ reference scala/Int#
//                        ^^^^^^^^ definition example/Extension$package.asString().
//                                  ^^^^^^ reference scala/Predef.String#
//                                           ^ reference example/Extension$package.asString().(i)
//                                             ^^^^^^^^ reference scala/Any#toString().

   extension (s: String)
//            ^ definition example/Extension$package.asInt().(s)
//            ^ definition example/Extension$package.double().(s)
//               ^^^^^^ reference scala/Predef.String#
     def asInt: Int = s.toInt
//       ^^^^^ definition example/Extension$package.asInt().
//              ^^^ reference scala/Int#
//                    ^ reference example/Extension$package.asInt().(s)
//                      ^^^^^ reference scala/collection/StringOps#toInt().
     def double: String = s * 2
//       ^^^^^^ definition example/Extension$package.double().
//               ^^^^^^ reference scala/Predef.String#
//                        ^ reference example/Extension$package.double().(s)
//                          ^ reference scala/collection/StringOps#`*`().

   trait AbstractExtension:
//       ^^^^^^^^^^^^^^^^^ definition example/AbstractExtension#
     extension (d: Double)
//   ^ definition example/AbstractExtension#`<init>`().
//              ^ definition example/AbstractExtension#abc().(d)
//                 ^^^^^^ reference scala/Double#
       def abc: String
//         ^^^ definition example/AbstractExtension#abc().
//              ^^^^^^ reference scala/Predef.String#
