package example

import io.circe.derivation.annotations.JsonCodec/*JsonCodec.scala*/

@JsonCodec/*MacroAnnotation.scala*/
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation/*<no symbol>*/(
    name/*MacroAnnotation.scala*/: String/*Predef.scala*/
) {
  def method/*MacroAnnotation.scala*/ = 42
}

object MacroAnnotations/*MacroAnnotation.scala*/ {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  val x/*MacroAnnotation.scala*/: Defn/*Trees.scala*/.Class/*Trees.scala*/ = Defn/*Trees.scala*/.Class/*Trees.scala*/(null, null, null, null, null)
  val y/*MacroAnnotation.scala*/: Mod/*Trees.scala*/.Final/*Trees.scala*/ = Mod/*Trees.scala*/.Final/*Trees.scala*/()
}
