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
    /*mods = */Nil,
    /*name = */Type.Name(/*value = */"test")/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/,
    /*tparams = */Nil,
    /*ctor = */Ctor.Primary(/*mods = */Nil, /*name = */Term.Name(/*value = */"this")/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/, /*paramss = */Nil)/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/,
    /*templ = */Template(/*early = */Nil, /*inits = */Nil, /*self = */Self(/*name = */Name.Anonymous()/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/, /*decltpe = */None), /*stats = */Nil),
  )/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/
  val y: Mod.Final = Mod.Final()/*(current<<scala/meta/internal/dialects/InternalDialect#current().>>)*/
}