package example

given intValue: Int = 4
given String = "str"
given (using i: Int): Double = 4.0
given [T]: List[T] = Nil
given given_Char: Char = '?'
given `given_Float`: Float = 3.0
given `* *` : Long = 5

def method(using Int)/*: String*/ = ""

object X:
  given Double = 4.0
  val double/*: Double*/ = given_Double

  given of[A]: Option[A] = ???

trait Xg:
  def doX: Int

trait Yg:
  def doY: String

trait Zg[T]:
  def doZ: List[T]

given Xg with
  def doX/*: Int*/ = 7

given (using Xg): Yg with
  def doY/*: String*/ = "7"

given [T]: Zg[T] with
  def doZ: List[T] = Nil

val a/*: Int*/ = intValue
val b/*: String*/ = given_String
val c/*: Double*/ = X.given_Double
val d/*: List[Int]*/ = given_List_T[Int]
val e/*: Char*/ = given_Char
val f/*: Float*/ = given_Float
val g/*: Long*/ = `* *`
val i/*: Option[Int]*/ = X.of[Int]
val x/*: given_Xg.type*/ = given_Xg
val y/*: given_Yg*/ = given_Yg/*(given_Xg)*/
val z/*: given_Zg_T[String]*/ = given_Zg_T[String]