package example

import io.circe.derivation.deriveDecoder
import io.circe.derivation.deriveEncoder

class Definitions {
  Predef.any2stringadd/*[Int<<scala/Int#>>]*/(1)
  List[
    java.util.Map.Entry[
      java.lang.Integer,
      java.lang.Double,
    ]
  ](
    elems = null
  )
  println(/*x = */deriveDecoder[MacroAnnotation])
  println(/*x = */deriveEncoder[MacroAnnotation])
}