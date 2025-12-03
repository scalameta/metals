package example

class Scalalib {
  val nil/*: List<<scala/collection/immutable/List#>>[Nothing<<scala/Nothing#>>]*/ = List/*[Nothing<<scala/Nothing#>>]*/()
  val lst/*: List<<scala/collection/immutable/List#>>[(Nothing<<scala/Nothing#>>, Null<<scala/Null#>>, Singleton<<scala/Singleton#>>, Any<<scala/Any#>>, AnyRef<<scala/AnyRef#>>, AnyVal<<scala/AnyVal#>>, Int<<scala/Int#>>, Short<<scala/Short#>>, Double<<scala/Double#>>, Float<<scala/Float#>>, Char<<scala/Char#>>)]*/ = List[
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
  ](/*elems = */null)
  lst.isInstanceOf[Any]
  lst.asInstanceOf[Any]
  println(/*x = */lst.##)
  lst ne lst
  lst eq lst
  lst == lst
}