package example

class Scalalib/*example.Scalalib#*/ {
  val nil/*example.Scalalib#nil.*/ = List/*scala.collection.immutable.Nil.*/()
  val lst/*example.Scalalib#lst.*/ = List/*scala.package.List.*/[
    (
        Nothing/*scala.Nothing#*/,
        Null/*scala.Null#*/,
        Singleton/*scala.Singleton#*/,
        Any/*scala.Any#*/,
        AnyRef/*scala.AnyRef#*/,
        AnyVal/*scala.AnyVal#*/,
        Int/*scala.Int#*/,
        Short/*scala.Short#*/,
        Double/*scala.Double#*/,
        Float/*scala.Float#*/,
        Char/*scala.Char#*/,
    )
  ](null)
  lst/*example.Scalalib#lst.*/.isInstanceOf/*scala.Any#isInstanceOf().*/[Any/*scala.Any#*/]
  lst/*example.Scalalib#lst.*/.asInstanceOf/*scala.Any#asInstanceOf().*/[Any/*scala.Any#*/]
  println/*scala.Predef.println(+1).*/(lst/*example.Scalalib#lst.*/.##/*java.lang.Object#`##`().*/)
  lst/*example.Scalalib#lst.*/ ne/*java.lang.Object#ne().*/ lst/*example.Scalalib#lst.*/
  lst/*example.Scalalib#lst.*/ eq/*java.lang.Object#eq().*/ lst/*example.Scalalib#lst.*/
  lst/*example.Scalalib#lst.*/ ==/*java.lang.Object#`==`().*/ lst/*example.Scalalib#lst.*/
}
