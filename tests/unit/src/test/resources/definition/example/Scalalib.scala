package example

class Scalalib/*Scalalib.scala*/ {
  val lst/*Scalalib.scala*/ = List/*List.scala*/[
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
        Char/*Char.scala*/
    )
  ]()
  lst/*Scalalib.scala*/.isInstanceOf[Any]
  lst/*Scalalib.scala*/.asInstanceOf[Any]
  println/*Predef.scala*/(lst/*Scalalib.scala*/.##/*Object.java*/)
  lst/*Scalalib.scala*/ ne/*Object.java*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ eq/*Object.java*/ lst/*Scalalib.scala*/
  lst/*Scalalib.scala*/ ==/*Object.java*/ lst/*Scalalib.scala*/
}
