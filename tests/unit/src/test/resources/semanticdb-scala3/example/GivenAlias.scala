package example

given intValue/*example.GivenAlias$package.intValue.*/: Int/*scala.Int#*/ = 4
given String/*scala.Predef.String#*/ = "str"
given (using Int/*scala.Int#*/): Double/*scala.Double#*/ = 4.0
given [T/*example.GivenAlias$package.given_List_T().[T]*/]: List/*scala.package.List#*/[T/*example.GivenAlias$package.given_List_T().[T]*/] = Nil/*scala.package.Nil.*/
given given_Char/*example.GivenAlias$package.given_Char.*/: Char/*scala.Char#*/ = '?'
given `given_Float/*example.GivenAlias$package.given_Float.*/`: Float/*scala.Float#*/ = 3.0
given `* */*example.GivenAlias$package.`* *`.*/`: Long/*scala.Long#*/ = 5

def method/*example.GivenAlias$package.method().*/(using Int/*scala.Int#*/) = ""

object X/*example.X.*/ {
  given Double/*scala.Double#*/ = 4.0
  val double/*example.X.double.*/ = given_Double/*example.X.given_Double.*/

  given of/*example.X.of().*/[A/*example.X.of().[A]*/]: Option/*scala.Option#*/[A/*example.X.of().[A]*/] = ???/*scala.Predef.`???`().*/
}

trait Xg/*example.Xg#*/:
  def doX/*example.Xg#doX().*/: Int/*scala.Int#*/

trait Yg/*example.Yg#*/:
  def doY/*example.Yg#doY().*/: String/*scala.Predef.String#*/

trait Zg/*example.Zg#*/[T/*example.Zg#[T]*/]:
  def doZ/*example.Zg#doZ().*/: List/*scala.package.List#*/[T/*example.Zg#[T]*/]

given Xg/*example.Xg#*/ with
  def doX/*example.GivenAlias$package.given_Xg.doX().*/ = 7

given (using Xg/*example.Xg#*/): Yg/*example.Yg#*/ with
  def doY/*example.GivenAlias$package.given_Yg#doY().*/ = "7"

given [T/*example.GivenAlias$package.given_Zg_T#[T]*/]: Zg/*example.Zg#*/[T/*example.GivenAlias$package.given_Zg_T#[T]*/] with
  def doZ/*example.GivenAlias$package.given_Zg_T#doZ().*/: List/*scala.package.List#*/[T/*example.GivenAlias$package.given_Zg_T#[T]*/] = Nil/*scala.package.Nil.*/


val a/*example.GivenAlias$package.a.*/ = intValue/*example.GivenAlias$package.intValue.*/
val b/*example.GivenAlias$package.b.*/ = given_String/*example.GivenAlias$package.given_String.*/
val c/*example.GivenAlias$package.c.*/ = X/*example.X.*/.given_Double/*example.X.given_Double.*/
val d/*example.GivenAlias$package.d.*/ = given_List_T/*example.GivenAlias$package.given_List_T().*/[Int/*scala.Int#*/]
val e/*example.GivenAlias$package.e.*/ = given_Char/*example.GivenAlias$package.given_Char.*/
val f/*example.GivenAlias$package.f.*/ = given_Float/*example.GivenAlias$package.given_Float.*/
val g/*example.GivenAlias$package.g.*/ = `* *`/*example.GivenAlias$package.`* *`.*/
val i/*example.GivenAlias$package.i.*/ = X/*example.X.*/.of/*example.X.of().*/[Int/*scala.Int#*/]
val x/*example.GivenAlias$package.x.*/ = given_Xg/*example.GivenAlias$package.given_Xg.*/
val y/*example.GivenAlias$package.y.*/ = given_Yg/*example.GivenAlias$package.given_Yg().*/
val z/*example.GivenAlias$package.z.*/ = given_Zg_T/*example.GivenAlias$package.given_Zg_T().*/[String/*scala.Predef.String#*/]

