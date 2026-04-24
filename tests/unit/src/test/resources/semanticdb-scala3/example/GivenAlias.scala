   package example
//         ^^^^^^^ definition example/

   given intValue: Int = 4
//       ^^^^^^^^ definition example/GivenAlias$package.intValue.
//                 ^^^ reference scala/Int#
   given String = "str"
//       ^^^^^^ reference scala/Predef.String#
   given (using i: Int): Double = 4.0
//              ^ definition example/GivenAlias$package.given_Double().(i)
//                 ^^^ reference scala/Int#
//                       ^^^^^^ reference scala/Double#
   given [T]: List[T] = Nil
//        ^ definition example/GivenAlias$package.given_List_T().[T]
//            ^^^^ reference scala/package.List#
//                 ^ reference example/GivenAlias$package.given_List_T().[T]
//                      ^^^ reference scala/package.Nil.
   given given_Char: Char = '?'
//       ^^^^^^^^^^ definition example/GivenAlias$package.given_Char.
//                   ^^^^ reference scala/Char#
   given `given_Float`: Float = 3.0
//        ^^^^^^^^^^^ definition example/GivenAlias$package.given_Float.
//                      ^^^^^ reference scala/Float#
   given `* *` : Long = 5
//        ^^^ definition example/GivenAlias$package.`* *`.
//               ^^^^ reference scala/Long#

   def method(using Int) = ""
//     ^^^^^^ definition example/GivenAlias$package.method().
//                  ^^^ reference scala/Int#

   object X:
//        ^ definition example/X.
     given Double = 4.0
//         ^^^^^^ reference scala/Double#
     val double = given_Double
//       ^^^^^^ definition example/X.double.
//                ^^^^^^^^^^^^ reference example/X.given_Double.

     given of[A]: Option[A] = ???
//         ^^ definition example/X.of().
//            ^ definition example/X.of().[A]
//                ^^^^^^ reference scala/Option#
//                       ^ reference example/X.of().[A]
//                            ^^^ reference scala/Predef.`???`().

   trait Xg:
//       ^^ definition example/Xg#
     def doX: Int
//   ^ definition example/Xg#`<init>`().
//       ^^^ definition example/Xg#doX().
//            ^^^ reference scala/Int#

   trait Yg:
//       ^^ definition example/Yg#
     def doY: String
//   ^ definition example/Yg#`<init>`().
//       ^^^ definition example/Yg#doY().
//            ^^^^^^ reference scala/Predef.String#

   trait Zg[T]:
//       ^^ definition example/Zg#
//         ^ definition example/Zg#`<init>`().
//          ^ definition example/Zg#[T]
     def doZ: List[T]
//       ^^^ definition example/Zg#doZ().
//            ^^^^ reference scala/package.List#
//                 ^ reference example/Zg#[T]

   given Xg with
//       ^^ reference example/Xg#
     def doX = 7
//       ^^^ definition example/GivenAlias$package.given_Xg.doX().

   given (using Xg): Yg with
//              ^ definition example/GivenAlias$package.given_Yg#`<init>`().
//              ^^ reference example/Xg#
//                   ^^ reference example/Yg#
     def doY = "7"
//       ^^^ definition example/GivenAlias$package.given_Yg#doY().

   given [T]: Zg[T] with
//        ^ definition example/GivenAlias$package.given_Zg_T#`<init>`().
//        ^ definition example/GivenAlias$package.given_Zg_T#[T]
//            ^^ reference example/Zg#
//               ^ reference example/GivenAlias$package.given_Zg_T#[T]
     def doZ: List[T] = Nil
//       ^^^ definition example/GivenAlias$package.given_Zg_T#doZ().
//            ^^^^ reference scala/package.List#
//                 ^ reference example/GivenAlias$package.given_Zg_T#[T]
//                      ^^^ reference scala/package.Nil.

   val a = intValue
//     ^ definition example/GivenAlias$package.a.
//         ^^^^^^^^ reference example/GivenAlias$package.intValue.
   val b = given_String
//     ^ definition example/GivenAlias$package.b.
//         ^^^^^^^^^^^^ reference example/GivenAlias$package.given_String.
   val c = X.given_Double
//     ^ definition example/GivenAlias$package.c.
//         ^ reference example/X.
//           ^^^^^^^^^^^^ reference example/X.given_Double.
   val d = given_List_T[Int]
//     ^ definition example/GivenAlias$package.d.
//         ^^^^^^^^^^^^ reference example/GivenAlias$package.given_List_T().
//                      ^^^ reference scala/Int#
   val e = given_Char
//     ^ definition example/GivenAlias$package.e.
//         ^^^^^^^^^^ reference example/GivenAlias$package.given_Char.
   val f = given_Float
//     ^ definition example/GivenAlias$package.f.
//         ^^^^^^^^^^^ reference example/GivenAlias$package.given_Float.
   val g = `* *`
//     ^ definition example/GivenAlias$package.g.
//         ^^^^^ reference example/GivenAlias$package.`* *`.
   val i = X.of[Int]
//     ^ definition example/GivenAlias$package.i.
//         ^ reference example/X.
//           ^^ reference example/X.of().
//              ^^^ reference scala/Int#
   val x = given_Xg
//     ^ definition example/GivenAlias$package.x.
//         ^^^^^^^^ reference example/GivenAlias$package.given_Xg.
   val y = given_Yg
//     ^ definition example/GivenAlias$package.y.
//         ^^^^^^^^ reference example/GivenAlias$package.given_Yg().
   val z = given_Zg_T[String]
//     ^ definition example/GivenAlias$package.z.
//         ^^^^^^^^^^ reference example/GivenAlias$package.given_Zg_T().
//                    ^^^^^^ reference scala/Predef.String#
