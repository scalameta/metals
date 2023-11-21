package example

class Scalalib {
  val nil/*: List[Nothing]*/ = List/*[Nothing]*/()
  val lst/*: List[(Nothing, Null, Singleton, Any, AnyRef, AnyVal, Int, Short, Double, Float, Char)]*/ = List[
    (
        Nothing,
        Null,
        Singleton,
        Any,
        AnyRef,
        AnyVal,
        Int,
        Short,
        Double,
        Float,
        Char,
    )
  ](null)
  lst.isInstanceOf[Any]
  lst.asInstanceOf[Any]
  println(lst.##)
  lst ne lst
  lst eq lst
  lst == lst
}