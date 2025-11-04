   package example
//         ^^^^^^^ definition example/

   class VarArgs {
//       ^^^^^^^ definition example/VarArgs#
//               ^ definition example/VarArgs#`<init>`().
     def add(a: Int*) = a
//       ^^^ definition example/VarArgs#add().
//           ^ definition example/VarArgs#add().(a)
//              ^^^ reference scala/Int#
//                      ^ reference example/VarArgs#add().(a)
     def add2(a: Seq[Int]*) = a
//       ^^^^ definition example/VarArgs#add2().
//            ^ definition example/VarArgs#add2().(a)
//               ^^^ reference scala/collection/immutable/Seq#
//                   ^^^ reference scala/Int#
//                            ^ reference example/VarArgs#add2().(a)
   }
