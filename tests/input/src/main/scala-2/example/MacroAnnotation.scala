package example

import io.circe.derivation.annotations.JsonCodec

@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  def method = 42
}

object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  val x: Defn.Class = Defn.Class(
    Nil,
    Type.Name("test"),
    Nil,
    Ctor.Primary(Nil, Term.Name("this"), Nil),
    Template(Nil, Nil, Self(Name.Anonymous(), None), Nil),
  )
  val y: Mod.Final = Mod.Final()
}
