package example

class ImplicitConversions/*ImplicitConversions.scala*/ {
  implicit def string2Number/*ImplicitConversions.scala*/(
      string/*ImplicitConversions.scala*/: String/*Predef.scala*/
  ): Int/*Int.scala*/ = 42
  val message/*ImplicitConversions.scala*/ = ""
  val number/*ImplicitConversions.scala*/ = 42
  val tuple/*ImplicitConversions.scala*/ = (1, 2)
  val char/*ImplicitConversions.scala*/: Char/*Char.scala*/ = 'a'

  // extension methods
  message/*ImplicitConversions.scala*/
    .stripSuffix/*StringOps.scala*/("h")
  tuple/*ImplicitConversions.scala*/ +/*Predef.scala*/ "Hello"

  // implicit conversions
  val x/*ImplicitConversions.scala*/: Int/*Int.scala*/ = message/*ImplicitConversions.scala*/

  // interpolators
  s/*StringContext.scala fallback to scala.StringContext#*/"Hello $message/*ImplicitConversions.scala*/ $number/*ImplicitConversions.scala*/"
  s/*StringContext.scala fallback to scala.StringContext#*/"""Hello
     |$message/*ImplicitConversions.scala*/
     |$number/*ImplicitConversions.scala*/""".stripMargin/*StringOps.scala*/

  val a/*ImplicitConversions.scala*/: Int/*Int.scala*/ = char/*ImplicitConversions.scala*/
  val b/*ImplicitConversions.scala*/: Long/*Long.scala*/ = char/*ImplicitConversions.scala*/
}
