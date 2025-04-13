package example

class ImplicitConversions {
  implicit def string2Number(
      string: String
  ): Int = 42
  val message/*: String<<java/lang/String#>>*/ = ""
  val number/*: Int<<scala/Int#>>*/ = 42
  val tuple/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (1, 2)
  val char: Char = 'a'

  // extension methods
  /*augmentString<<scala/Predef.augmentString().>>(*/message/*)*/
    .stripSuffix(/*suffix = */"h")
  /*any2stringadd<<scala/Predef.any2stringadd().>>(*/tuple/*)*/ + "Hello"

  // implicit conversions
  val x: Int = /*string2Number<<(3:15)>>(*/message/*)*/

  // interpolators
  s"Hello $message $number"
  /*augmentString<<scala/Predef.augmentString().>>(*/s"""Hello
     |$message
     |$number"""/*)*/.stripMargin

  val a: Int = char
  val b: Long = char
}