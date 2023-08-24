package example

class ImplicitConversions {
  implicit def string2Number(
      string: String
  ): Int = 42
  val message/*: String*/ = ""
  val number/*: Int*/ = 42
  val tuple/*: (Int, Int)*/ = (1, 2)
  val char: Char = 'a'

  // extension methods
  /*augmentString(*/message/*)*/
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  val x: Int = /*string2Number(*/message/*)*/

  // interpolators
  s"Hello $message $number"
  /*augmentString(*/s"""Hello
     |$message
     |$number"""/*)*/.stripMargin

  val a: Int = char
  val b: Long = char
}