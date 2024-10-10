package example

class Scalalib/*Scalalib.scala*/ {
  val nil/*Scalalib.scala*/ = List/*package.scala*/()
  val lst/*Scalalib.scala*/ = List/*package.scala*/[
    (
        Nothing/*Nothing.scala*/,
        Null/*Null.scala*/,
        Singleton/*Singleton.scala*/,
        Any/*Any.scala*/,
        AnyRef/*AnyRef.scala*/,
        AnyVal/*AnyVal.scala*/,
        Int/*Int.scala*/,
        Short/*Short.scala*/,
        Double/*Double.scala*/,
        Float/*Float.scala*/,
        Char/*Char.scala*/,/*unexpected: scala.Char#*/
    )
  ](null)
  lst/*Scalalib.scala*/.isInstanceOf/*Any.scala*/[Any/*Any.scala*/]
  lst/*Scalalib.scala*/.asInstanceOf/*Any.scala*/[Any/*Any.scala*/]
  println/*Predef.scala*/(lst/*Scalalib.scala*/.##/*Any.scala*/)
  lst/*Scalalib.scala*/ ne/*Object.java fallback to java.lang.Object#*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ eq/*Object.java fallback to java.lang.Object#*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ ==/*Any.scala*/ lst/*Scalalib.scala*/
}
