/*example*/package example

import io.circe.derivation.annotations.JsonCodec

/*MacroAnnotation*/@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  /*method*/def method = 42
}

/*MacroAnnotations*/object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  /*x*/val x: Defn.Class = Defn.Class(null, null, null, null, null)
  /*y*/val y: Mod.Final = Mod.Final()
}
