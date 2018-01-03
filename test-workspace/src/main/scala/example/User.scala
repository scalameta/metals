package a

case class User(name: String, age: Int)

object a {
  val x = "ba"
  val y = List(1, x).length
  def z = {
    val localSymbol = "222" // can be renamed
    localSymbol.length
  }
}