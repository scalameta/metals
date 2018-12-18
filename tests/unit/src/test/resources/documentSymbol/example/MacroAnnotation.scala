/*example:18*/package example

import io.circe.derivation.annotations.JsonCodec

/*MacroAnnotation:10*/@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  /*method:9*/def method = 42
}

/*MacroAnnotations:18*/object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  /*x:16*/val x: Defn.Class = Defn.Class(null, null, null, null, null)
  /*y:17*/val y: Mod.Final = Mod.Final()
}
