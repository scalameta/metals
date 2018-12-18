/*example(Package):26*/package example

/*Scalalib(Class):26*/class Scalalib {
  /*nil(Constant):4*/val nil = List()
  /*lst(Constant):19*/val lst = List[
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
  ](null)
  lst.isInstanceOf[Any]
  lst.asInstanceOf[Any]
  println(lst.##)
  lst ne lst
  lst eq lst
  lst == lst
}
