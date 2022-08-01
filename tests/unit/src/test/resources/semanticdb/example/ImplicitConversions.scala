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
  message/*example.ImplicitConversions#message.*/
    .stripSuffix/*scala.collection.StringOps#stripSuffix().*/("h")
  tuple/*example.ImplicitConversions#tuple.*/ +/*scala.Predef.any2stringadd#`+`().*/ "Hello"

  // implicit conversions
  val x/*example.ImplicitConversions#x.*/: Int/*scala.Int#*/ = message/*example.ImplicitConversions#message.*/

  // interpolators
  s/*scala.StringContext#s().*/"Hello $message/*example.ImplicitConversions#message.*/ $number/*example.ImplicitConversions#number.*/"
  s/*scala.StringContext#s().*/"""Hello
     |$message/*example.ImplicitConversions#message.*/
     |$number/*example.ImplicitConversions#number.*/""".stripMargin/*scala.collection.StringOps#stripMargin(+1).*/

  val a/*example.ImplicitConversions#a.*/: Int/*scala.Int#*/ = char/*example.ImplicitConversions#char.*/
  val b/*example.ImplicitConversions#b.*/: Long/*scala.Long#*/ = char/*example.ImplicitConversions#char.*/
}
