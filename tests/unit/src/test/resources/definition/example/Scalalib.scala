package example

class Scalalib/*Scalalib.scala*/ {
  val nil/*Scalalib.scala*/ = List/*List.scala*/()
  val lst/*Scalalib.scala*/ = List/*package.scala*/[
    (
        Nothing,
        Null,
        Singleton,
        Any,
        AnyRef,
        AnyVal/*AnyVal.scala*/,
        Int/*Int.scala*/,
        Short/*Short.scala*/,
        Double/*Double.scala*/,
        Float/*Float.scala*/,
        Char/*Char.scala*/,
    )
  ](null)
  lst/*Scalalib.scala*/.isInstanceOf[Any]
  lst/*Scalalib.scala*/.asInstanceOf[Any]
  println/*Predef.scala*/(lst/*Scalalib.scala*/.##/*Object.java fallback to java.lang.Object#*/)
  lst/*Scalalib.scala*/ ne/*Object.java fallback to java.lang.Object#*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ eq/*Object.java fallback to java.lang.Object#*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ ==/*Object.java fallback to java.lang.Object#*/ lst/*Scalalib.scala*/
}
