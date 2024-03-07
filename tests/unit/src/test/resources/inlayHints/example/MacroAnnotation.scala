package example

import io.circe.derivation.annotations.JsonCodec

@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  def method/*: Int<<scala/Int#>>*/ = 42
}

object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  val x: Defn.Class = Defn.Class(
    Nil,
    Type.Name("test")/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/,
    Nil,
    Ctor.Primary(Nil, Term.Name("this")/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/, Nil)/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/,
    Template(Nil, Nil, Self(Name.Anonymous()/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/, None), Nil),
  )/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/
  val y: Mod.Final = Mod.Final()/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/
}