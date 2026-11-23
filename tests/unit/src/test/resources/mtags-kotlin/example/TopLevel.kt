   @file:JvmName("TopLevelUtils")

   package example.toplevel
//         ^^^^^^^ reference example/
//                 ^^^^^^^^ reference example/toplevel/

   import example.User

   const val VERSION: String = "1.0"
//           ^^^^^^^ definition example/toplevel/VERSION. METHOD(val)
//           ^^^^^^^ definition example/toplevel/getVERSION(). METHOD

   var counter = 0
//     ^^^^^^^ definition example/toplevel/counter. METHOD(var)
//     ^^^^^^^ definition example/toplevel/getCounter(). METHOD
//     ^^^^^^^ definition example/toplevel/setCounter(). METHOD

   val String.shout: String
//            ^^^^^ definition example/toplevel/shout. METHOD(val)
//            ^^^^^ definition example/toplevel/getShout(). METHOD
       get() = uppercase() + "!"

   fun <T> T.describe(): String = toString()
//           ^^^^^^^^ definition example/toplevel/describe(). METHOD

   fun Map<String, List<Int>>.total(): Int = values.sumOf { it.sum() }
//                            ^^^^^ definition example/toplevel/total(). METHOD

   fun User?.orEmpty(): String = this?.name ?: ""
//           ^^^^^^^ definition example/toplevel/orEmpty(). METHOD

   fun `name with spaces`() = Unit
//      ^^^^^^^^^^^^^^^^ definition example/toplevel/`name with spaces`(). METHOD

   suspend fun fetch(id: Int): String = "id=$id"
//             ^^^^^ definition example/toplevel/fetch(). METHOD

   fun overloaded() = 1
//     ^^^^^^^^^^ definition example/toplevel/overloaded(). METHOD
   fun overloaded(x: Int) = x
//     ^^^^^^^^^^ definition example/toplevel/overloaded(+1). METHOD
   fun overloaded(x: String) = x.length//     ^^^^^^^^^^ definition example/toplevel/overloaded(+2). METHOD
