package example

class ImplicitConversions/*ImplicitConversions.semanticdb*/ {
  implicit def string2Number/*ImplicitConversions.semanticdb*/(
      string/*ImplicitConversions.semanticdb*/: String/*Predef.scala*/
  ): Int/*Int.scala*/ = 42
  val message/*ImplicitConversions.semanticdb*/ = ""
  val number/*ImplicitConversions.semanticdb*/ = 42
  val tuple/*ImplicitConversions.semanticdb*/ = (1, 2)
  val char/*ImplicitConversions.semanticdb*/: Char/*Char.scala*/ = 'a'

  // extension methods
  message/*Predef.scala*/
    .stripSuffix/*StringLike.scala*/("h")
  tuple/*Predef.scala*/ +/*Predef.scala*/ "Hello"

  // implicit conversions
  val x/*ImplicitConversions.semanticdb*/: Int/*Int.scala*/ = message/*ImplicitConversions.semanticdb*/

  // interpolators
  s/*StringContext.scala*/"Hello $message/*ImplicitConversions.semanticdb*/ $number/*ImplicitConversions.semanticdb*/"
  s/*Predef.scala*/"""Hello
     |$message/*ImplicitConversions.semanticdb*/
     |$number/*ImplicitConversions.semanticdb*/""".stripMargin/*StringLike.scala*/

  val a/*ImplicitConversions.semanticdb*/: Int/*Int.scala*/ = char/*Char.scala*/
  val b/*ImplicitConversions.semanticdb*/: Long/*Long.scala*/ = char/*Char.scala*/
}
