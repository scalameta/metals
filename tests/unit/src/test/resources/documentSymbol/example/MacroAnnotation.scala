/*example(Package):19*/package example

import io.circe.derivation.annotations.JsonCodec

/*MacroAnnotation(Class):11*/@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  /*method(Method):10*/def method = 42
}

/*MacroAnnotations(Module):19*/object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  /*x(Constant):17*/val x: Defn.Class = Defn.Class(null, null, null, null, null)
  /*y(Constant):18*/val y: Mod.Final = Mod.Final()
}
