package example

given intValue: Int = 4
given String = "str"
given (using Int): Double = 4.0
given [T]: List[T] = Nil
given given_Char: Char = '?'
given `given_Float`: Float = 3.0
given `* *`: Long = 5

def method(using Int) = ""

object X {
  given Double = 4.0
  val double = given_Double

  given of[A]: Option[A] = ???
}

trait Xg:
  def doX: Int

trait Yg:
  def doY: String

trait Zg[T]:
  def doZ: List[T]

given Xg with
  def doX = 7

given (using Xg): Yg with
  def doY = "7"

given [T]: Zg[T] with
  def doZ: List[T] = Nil


val a = intValue
val b = given_String
val c = X.given_Double
val d = given_List_T[Int]
val e = given_Char
val f = given_Float
val g = `* *`
val i = X.of[Int]
val x = given_Xg
val y = given_Yg
val z = given_Zg_T[String]

