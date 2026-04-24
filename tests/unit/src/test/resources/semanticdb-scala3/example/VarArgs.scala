   package example
//         ^^^^^^^ definition example/

   class VarArgs {
//       ^^^^^^^ definition example/VarArgs#
     def add(a: Int*) = a
//   ^ definition example/VarArgs#`<init>`().
//       ^^^ definition example/VarArgs#add().
//           ^ definition example/VarArgs#add().(a)
//              ^^^ reference scala/Int#
//                      ^ reference example/VarArgs#add().(a)
     def add2(a: Seq[Int]*) = a
//       ^^^^ definition example/VarArgs#add2().
//            ^ definition example/VarArgs#add2().(a)
//               ^^^ reference scala/package.Seq#
//                   ^^^ reference scala/Int#
//                            ^ reference example/VarArgs#add2().(a)
   }
