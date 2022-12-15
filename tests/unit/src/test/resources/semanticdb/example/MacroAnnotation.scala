package example

import io.circe.derivation.annotations.JsonCodec/*io.circe.derivation.annotations.JsonCodec.*//*io.circe.derivation.annotations.JsonCodec#*/

@/*local1*/JsonCodec/*example.MacroAnnotation#*/
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
  val x/*example.MacroAnnotations.x.*/: Defn/*scala.meta.Defn.*/.Class/*scala.meta.Defn.Class#*/ = Defn/*scala.meta.Defn.*/.Class/*scala.meta.Defn.Class.*/(
    Nil/*scala.package.Nil.*/,
    Type/*scala.meta.Type.*/.Name/*scala.meta.Type.Name.*/("test"),
    Nil/*scala.package.Nil.*/,
    Ctor/*scala.meta.Ctor.*/.Primary/*scala.meta.Ctor.Primary.*/(Nil/*scala.package.Nil.*/, Term/*scala.meta.Term.*/.Name/*scala.meta.Term.Name.*/("this"), Nil/*scala.package.Nil.*/),
    Template/*scala.meta.Template.*/(Nil/*scala.package.Nil.*/, Nil/*scala.package.Nil.*/, Self/*scala.meta.Self.*/(Name/*scala.meta.Name.*/.Anonymous/*scala.meta.Name.Anonymous.*/(), None/*scala.None.*/), Nil/*scala.package.Nil.*/),
  )
  val y/*example.MacroAnnotations.y.*/: Mod/*scala.meta.Mod.*/.Final/*scala.meta.Mod.Final#*/ = Mod/*scala.meta.Mod.*/.Final/*scala.meta.Mod.Final.*/()
}
