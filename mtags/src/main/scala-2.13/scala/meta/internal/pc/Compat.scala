package scala.meta.internal.pc

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] =
    definitions.functionOrPfOrSamArgTypes(tpe)

  val AssignOrNamedArg: NamedArgExtractor = NamedArg
}
