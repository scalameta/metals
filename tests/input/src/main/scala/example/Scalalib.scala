package example

class Scalalib {
  val lst = List[
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
        Char
    )
  ]()
  lst.isInstanceOf[Any]
  lst.asInstanceOf[Any]
  println(lst.##)
  lst ne lst
  lst eq lst
  lst == lst
}
