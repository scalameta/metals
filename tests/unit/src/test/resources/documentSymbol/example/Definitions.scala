/*example(Package):18*/package example

import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder

/*example.Definitions(Class):18*/class Definitions {
  Predef.any2stringadd(1)
  List[
    java.util.Map.Entry[
      java.lang.Integer,
      java.lang.Double,
    ]
  ](
    elems = null
  )
  println(deriveDecoder[MacroAnnotation])
  println(deriveEncoder[MacroAnnotation])
}