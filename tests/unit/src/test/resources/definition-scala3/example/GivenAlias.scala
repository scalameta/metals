package example

given intValue/*GivenAlias.scala*/: Int/*Int.scala*/ = 4
given String/*Predef.scala*/ = "str"

val anonUsage/*GivenAlias.scala*/ = given_String/*GivenAlias.scala*/

object X/*GivenAlias.scala*/ {
  given Double/*Double.scala*/ = 4.0
  val double/*GivenAlias.scala*/ = given_Double/*GivenAlias.scala*/
}
