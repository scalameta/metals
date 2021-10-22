package example

given intValue/*example.GivenAlias$package.intValue.*/: Int/*scala.Int#*/ = 4
given String/*scala.Predef.String#*/ = "str"

def method/*example.GivenAlias$package.method().*/(using Int/*scala.Int#*/) = ""

val anonUsage/*example.GivenAlias$package.anonUsage.*/ = given_String/*example.GivenAlias$package.given_String.*/

object X/*example.X.*/ {
  given Double/*scala.Double#*/ = 4.0
  val double/*example.X.double.*/ = given_Double/*example.X.given_Double.*/
}
