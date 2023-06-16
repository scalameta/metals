/*example(Package):25*/package example

import io.circe.derivation.annotations.JsonCodec

/*example.MacroAnnotation(Class):11*/@JsonCodec
// FIXME: https://github.com/scalameta/scalameta/issues/1789
case class MacroAnnotation(
    name: String
) {
  /*example.MacroAnnotation#method(Method):10*/def method = 42
}

/*example.MacroAnnotations(Module):25*/object MacroAnnotations {
  import scala.meta._
  // IntelliJ has never managed to goto definition for the inner classes from Trees.scala
  // due to the macro annotations.
  /*example.MacroAnnotations.x(Constant):23*/val x: Defn.Class = Defn.Class(
    Nil,
    Type.Name("test"),
    Nil,
    Ctor.Primary(Nil, Term.Name("this"), Nil),
    Template(Nil, Nil, Self(Name.Anonymous(), None), Nil),
  )
  /*example.MacroAnnotations.y(Constant):24*/val y: Mod.Final = Mod.Final()
}
