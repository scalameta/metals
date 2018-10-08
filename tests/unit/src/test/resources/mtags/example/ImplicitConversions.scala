package example

class ImplicitConversions/*example.ImplicitConversions#*/ {
  implicit def string2Number/*example.ImplicitConversions#string2Number().*/(
      string/*example.ImplicitConversions#string2Number().(string)*/: String
  ): Int = 42
  val message/*example.ImplicitConversions#message.*/ = ""
  val number/*example.ImplicitConversions#number.*/ = 42
  val tuple/*example.ImplicitConversions#tuple.*/ = (1, 2)
  val char/*example.ImplicitConversions#char.*/: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  val x/*example.ImplicitConversions#x.*/: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  val a/*example.ImplicitConversions#a.*/: Int = char
  val b/*example.ImplicitConversions#b.*/: Long = char
}
