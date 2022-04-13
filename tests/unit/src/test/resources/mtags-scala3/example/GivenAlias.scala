/*example.GivenAlias$package.*/package example

given intValue/*example.GivenAlias$package.intValue.*/: Int = 4
given /*example.GivenAlias$package.given_String.*/String = "str"
given /*example.GivenAlias$package.given_Double().*/(using i/*example.GivenAlias$package.given_Double().(i)*/: Int): Double = 4.0
given /*example.GivenAlias$package.given_List_T().*/[T/*example.GivenAlias$package.given_List_T().[T]*/]: List[T] = Nil
given given_Char/*example.GivenAlias$package.given_Char.*/: Char = '?'
given `given_Float`/*example.GivenAlias$package.given_Float.*/: Float = 3.0
given `* *`/*example.GivenAlias$package.`* *`.*/: Long = 5

def method/*example.GivenAlias$package.method().*/(using /*example.GivenAlias$package.method().(``)*/Int) = ""

object X/*example.X.*/ {
  given /*example.X.given_Double.*/Double = 4.0
  val double/*example.X.double.*/ = given_Double

  given of/*example.X.of().*/[A/*example.X.of().[A]*/]: Option[A] = ???
}

trait Xg/*example.Xg#*/:
  def doX/*example.Xg#doX().*/: Int

trait Yg/*example.Yg#*/:
  def doY/*example.Yg#doY().*/: String

trait Zg/*example.Zg#*/[T/*example.Zg#[T]*/]:
  def doZ/*example.Zg#doZ().*/: List[T]

given Xg/*example.GivenAlias$package.given_Xg.*/ with
  def doX/*example.GivenAlias$package.given_Xg.doX().*/ = 7

given (using /*example.GivenAlias$package.given_Yg#(``)*/Xg): Yg/*example.GivenAlias$package.given_Yg().*/ with
  def doY/*example.GivenAlias$package.given_Yg#doY().*/ = "7"

given [T/*example.GivenAlias$package.given_Zg_T#[T]*/]: Zg[T]/*example.GivenAlias$package.given_Zg_T().*/ with
  def doZ/*example.GivenAlias$package.given_Zg_T#doZ().*/: List[T] = Nil


val a/*example.GivenAlias$package.a.*/ = intValue
val b/*example.GivenAlias$package.b.*/ = given_String
val c/*example.GivenAlias$package.c.*/ = X.given_Double
val d/*example.GivenAlias$package.d.*/ = given_List_T[Int]
val e/*example.GivenAlias$package.e.*/ = given_Char
val f/*example.GivenAlias$package.f.*/ = given_Float
val g/*example.GivenAlias$package.g.*/ = `* *`
val i/*example.GivenAlias$package.i.*/ = X.of[Int]
val x/*example.GivenAlias$package.x.*/ = given_Xg
val y/*example.GivenAlias$package.y.*/ = given_Yg
val z/*example.GivenAlias$package.z.*/ = given_Zg_T[String]

