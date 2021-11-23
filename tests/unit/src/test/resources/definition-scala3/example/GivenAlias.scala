package example

given intValue/*GivenAlias.scala*/: Int/*Int.scala*/ = 4
given String/*Predef.scala*/ = "str"
given (using/*<no symbol>*/ Int/*Int.scala*/): Double/*Double.scala*/ = 4.0
given [T/*GivenAlias.scala*/]: List/*package.scala*/[T/*GivenAlias.scala*/] = Nil/*package.scala*/
given given_Char/*GivenAlias.scala*/: Char/*Char.scala*/ = '?'
given `given_Float`/*<no symbol>*/: Float/*Float.scala*/ = 3.0
given `* *`/*<no symbol>*/: Long/*Long.scala*/ = 5

def method/*GivenAlias.scala*/(using/*<no symbol>*/ Int/*Int.scala*/) = ""

object X/*GivenAlias.scala*/ {
  given Double/*Double.scala*/ = 4.0
  val double/*GivenAlias.scala*/ = given_Double/*GivenAlias.scala*/

  given of/*GivenAlias.scala*/[A/*GivenAlias.scala*/]: Option/*Option.scala*/[A/*GivenAlias.scala*/] = ???/*Predef.scala*/
}

trait Xg/*GivenAlias.scala*/:
  def doX/*GivenAlias.scala*/: Int/*Int.scala*/

trait Yg/*GivenAlias.scala*/:
  def doY/*GivenAlias.scala*/: String/*Predef.scala*/

trait Zg/*GivenAlias.scala*/[T/*GivenAlias.scala*/]:
  def doZ/*GivenAlias.scala*/: List/*package.scala*/[T/*GivenAlias.scala*/]

given Xg/*GivenAlias.scala*/ with
  def doX/*GivenAlias.scala*/ = 7

given (using/*<no symbol>*/ Xg/*GivenAlias.scala*/): Yg/*GivenAlias.scala*/ with
  def doY/*<no file>*/ = "7"

given [T/*<no file>*/]: Zg/*GivenAlias.scala*/[T/*<no file>*/] with
  def doZ/*<no file>*/: List/*package.scala*/[T/*<no file>*/] = Nil/*package.scala*/


val a/*GivenAlias.scala*/ = intValue/*GivenAlias.scala*/
val b/*GivenAlias.scala*/ = given_String/*GivenAlias.scala*/
val c/*GivenAlias.scala*/ = X/*GivenAlias.scala*/.given_Double/*GivenAlias.scala*/
val d/*GivenAlias.scala*/ = given_List_T/*GivenAlias.scala*/[Int/*Int.scala*/]
val e/*GivenAlias.scala*/ = given_Char/*GivenAlias.scala*/
val f/*GivenAlias.scala*/ = given_Float/*GivenAlias.scala*/
val g/*GivenAlias.scala*/ = `* *`/*GivenAlias.scala*/
val i/*GivenAlias.scala*/ = X/*GivenAlias.scala*/.of/*GivenAlias.scala*/[Int/*Int.scala*/]
val x/*GivenAlias.scala*/ = given_Xg/*GivenAlias.scala*/
val y/*GivenAlias.scala*/ = given_Yg/*GivenAlias.scala*/
val z/*GivenAlias.scala*/ = given_Zg_T/*GivenAlias.scala*/[String/*Predef.scala*/]

