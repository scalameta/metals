package example

given intValue: Int = 4
given String = "str"
given (using i: Int): Double = 4.0
given [T]: List[T] = Nil
given given_Char: Char = '?'
given `given_Float`: Float = 3.0
given `* *` : Long = 5

def method(using Int)/*: String<<java/lang/String#>>*/ = ""

object X:
  given Double = 4.0
  val double/*: Double<<scala/Double#>>*/ = given_Double

  given of[A]: Option[A] = ???

trait Xg:
  def doX: Int

trait Yg:
  def doY: String

trait Zg[T]:
  def doZ: List[T]

given Xg with
  def doX/*: Int<<scala/Int#>>*/ = 7

given (using Xg): Yg with
  def doY/*: String<<scala/Predef.String#>>*/ = "7"

given [T]: Zg[T] with
  def doZ: List[T] = Nil

val a/*: Int<<scala/Int#>>*/ = intValue
val b/*: String<<scala/Predef.String#>>*/ = given_String
val c/*: Double<<scala/Double#>>*/ = X.given_Double
val d/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = given_List_T[Int]
val e/*: Char<<scala/Char#>>*/ = given_Char
val f/*: Float<<scala/Float#>>*/ = given_Float
val g/*: Long<<scala/Long#>>*/ = `* *`
val i/*: Option<<scala/Option#>>[Int<<scala/Int#>>]*/ = X.of[Int]
val x/*: given_Xg<<(27:6)>>.type*/ = given_Xg
val y/*: given_Yg<<(30:6)>>*/ = given_Yg/*(using given_Xg<<(27:0)>>)*/
val z/*: given_Zg_T<<(33:6)>>[String<<scala/Predef.String#>>]*/ = given_Zg_T[String]