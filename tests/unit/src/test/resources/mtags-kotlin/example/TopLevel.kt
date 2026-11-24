   @file:JvmName("TopLevelUtils")

   package example.toplevel
//         ^^^^^^^ reference example/
//                 ^^^^^^^^ reference example/toplevel/

   import example.User

   const val VERSION: String = "1.0"
//           ^^^^^^^ definition example/toplevel/TopLevelUtils# CLASS(final, static)
//           ^^^^^^^ definition example/toplevel/VERSION. METHOD(val)
//           ^^^^^^^ definition example/toplevel/getVERSION(). METHOD
//           ^^^^^^^ definition example/toplevel/TopLevelUtils#VERSION. METHOD(val, static)
//           ^^^^^^^ definition example/toplevel/TopLevelUtils#getVERSION(). METHOD(static)

   var counter = 0
//     ^^^^^^^ definition example/toplevel/counter. METHOD(var)
//     ^^^^^^^ definition example/toplevel/getCounter(). METHOD
//     ^^^^^^^ definition example/toplevel/setCounter(). METHOD
//     ^^^^^^^ definition example/toplevel/TopLevelUtils#counter. METHOD(var, static)
//     ^^^^^^^ definition example/toplevel/TopLevelUtils#getCounter(). METHOD(static)
//     ^^^^^^^ definition example/toplevel/TopLevelUtils#setCounter(). METHOD(static)

   val String.shout: String
//            ^^^^^ definition example/toplevel/shout. METHOD(val)
//            ^^^^^ definition example/toplevel/getShout(). METHOD
//            ^^^^^ definition example/toplevel/TopLevelUtils#shout. METHOD(val, static)
//            ^^^^^ definition example/toplevel/TopLevelUtils#getShout(). METHOD(static)
       get() = uppercase() + "!"

   fun <T> T.describe(): String = toString()
//           ^^^^^^^^ definition example/toplevel/describe(). METHOD
//           ^^^^^^^^ definition example/toplevel/TopLevelUtils#describe(). METHOD(static)

   fun Map<String, List<Int>>.total(): Int = values.sumOf { it.sum() }
//                            ^^^^^ definition example/toplevel/total(). METHOD
//                            ^^^^^ definition example/toplevel/TopLevelUtils#total(). METHOD(static)

   fun User?.orEmpty(): String = this?.name ?: ""
//           ^^^^^^^ definition example/toplevel/orEmpty(). METHOD
//           ^^^^^^^ definition example/toplevel/TopLevelUtils#orEmpty(). METHOD(static)

   fun `name with spaces`() = Unit
//      ^^^^^^^^^^^^^^^^ definition example/toplevel/`name with spaces`(). METHOD
//      ^^^^^^^^^^^^^^^^ definition example/toplevel/TopLevelUtils#`name with spaces`(). METHOD(static)

   suspend fun fetch(id: Int): String = "id=$id"
//             ^^^^^ definition example/toplevel/fetch(). METHOD
//             ^^^^^ definition example/toplevel/TopLevelUtils#fetch(). METHOD(static)

   fun overloaded() = 1
//     ^^^^^^^^^^ definition example/toplevel/overloaded(). METHOD
//     ^^^^^^^^^^ definition example/toplevel/TopLevelUtils#overloaded(). METHOD(static)
   fun overloaded(x: Int) = x
//     ^^^^^^^^^^ definition example/toplevel/overloaded(+1). METHOD
//     ^^^^^^^^^^ definition example/toplevel/TopLevelUtils#overloaded(+1). METHOD(static)
   fun overloaded(x: String) = x.length//     ^^^^^^^^^^ definition example/toplevel/overloaded(+2). METHOD
//     ^^^^^^^^^^ definition example/toplevel/TopLevelUtils#overloaded(+2). METHOD(static)
