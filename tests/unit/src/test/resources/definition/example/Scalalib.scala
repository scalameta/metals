package example

class Scalalib/*Scalalib.semanticdb*/ {
  val lst/*Scalalib.semanticdb*/ = List/*List.scala*/[
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
  lst/*Scalalib.semanticdb*/.isInstanceOf[Any]
  lst/*Scalalib.semanticdb*/.asInstanceOf[Any]
  println/*Predef.scala*/(lst/*Scalalib.semanticdb*/.##/*Object.java*/)
  lst/*Scalalib.semanticdb*/ ne/*Object.java*/ lst/*Scalalib.semanticdb*/
  lst/*Scalalib.semanticdb*/ eq/*Object.java*/ lst/*Scalalib.semanticdb*/
  lst/*Scalalib.semanticdb*/ ==/*Object.java*/ lst/*Scalalib.semanticdb*/
}
