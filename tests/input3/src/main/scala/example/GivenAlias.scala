package example

given intValue: Int = 4
given String = "str"

def method(using Int) = ""

val anonUsage = given_String

object X {
  given Double = 4.0
  val double = given_Double
}
