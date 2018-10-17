package example

import io.circe.derivation.annotations.JsonCodec/*io.circe.derivation.annotations.JsonCodec.*//*io.circe.derivation.annotations.JsonCodec#*/

@JsonCodec/*example.MacroAnnotation#*/
// FIXME: https://github.com/scalameta/scalameta/issues/1789/*java.lang.Object#`<init>`().*/
case class MacroAnnotation(
    name/*example.MacroAnnotation#name.*/: String/*scala.Predef.String#*/
) {
  def method/*example.MacroAnnotation#method().*/ = 42
}

object MacroAnnotations/*example.MacroAnnotations.*/ {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  val x/*example.MacroAnnotations.x.*/: Defn/*scala.meta.Defn.*/.Class/*scala.meta.Defn.Class#*/ = Defn/*scala.meta.Defn.*/.Class/*scala.meta.Defn.Class.*/(null, null, null, null, null)
  val y/*example.MacroAnnotations.y.*/: Mod/*scala.meta.Mod.*/.Final/*scala.meta.Mod.Final#*/ = Mod/*scala.meta.Mod.*/.Final/*scala.meta.Mod.Final.*/()
}
