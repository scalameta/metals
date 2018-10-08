package example

class ImplicitConversions/*example.ImplicitConversions#*/ {
  implicit def string2Number/*example.ImplicitConversions#string2Number().*/(
      string/*example.ImplicitConversions#string2Number().(string)*/: String/*scala.Predef.String#*/
  ): Int/*scala.Int#*/ = 42
  val message/*example.ImplicitConversions#message.*/ = ""
  val number/*example.ImplicitConversions#number.*/ = 42
  val tuple/*example.ImplicitConversions#tuple.*/ = (1, 2)
  val char/*example.ImplicitConversions#char.*/: Char/*scala.Char#*/ = 'a'

  // extension methods
  message/*scala.Predef.augmentString().*/
    .stripSuffix/*scala.collection.immutable.StringLike#stripSuffix().*/("h")
  tuple/*scala.Predef.any2stringadd().*/ +/*scala.Predef.any2stringadd#`+`().*/ "Hello"

  // implicit conversions
  val x/*example.ImplicitConversions#x.*/: Int/*scala.Int#*/ = message/*example.ImplicitConversions#string2Number().*/

  // interpolators
  s/*scala.StringContext#s().*/"Hello $message/*example.ImplicitConversions#message.*/ $number/*example.ImplicitConversions#number.*/"
  s/*scala.Predef.augmentString().*/"""Hello
     |$message/*example.ImplicitConversions#message.*/
     |$number/*example.ImplicitConversions#number.*/""".stripMargin/*scala.collection.immutable.StringLike#stripMargin(+1).*/

  val a/*example.ImplicitConversions#a.*/: Int/*scala.Int#*/ = char/*scala.Char#toInt().*/
  val b/*example.ImplicitConversions#b.*/: Long/*scala.Long#*/ = char/*scala.Char#toLong().*/
}
