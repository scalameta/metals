package example

import io.circe.derivation.deriveDecoder/*package.scala fallback to io.circe.derivation.package.*/
import io.circe.derivation.deriveEncoder/*package.scala fallback to io.circe.derivation.package.*/

class Definitions/*Definitions.scala*/ {
  Predef/*Predef.scala*/.any2stringadd/*Predef.scala*/(1)
  List/*package.scala*/[
    java.util.Map/*Map.java*/.Entry/*Map.java*/[
      java.lang.Integer/*Integer.java*/,
      java.lang.Double/*Double.java*/,
    ]
  ](
    elems/*Factory.scala*/ = null
  )
  println/*Predef.scala*/(deriveDecoder/*package.scala fallback to io.circe.derivation.package.*/[MacroAnnotation/*MacroAnnotation.scala*/])
  println/*Predef.scala*/(deriveEncoder/*package.scala fallback to io.circe.derivation.package.*/[MacroAnnotation/*MacroAnnotation.scala*/])
}