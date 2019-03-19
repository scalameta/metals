package scala.meta.internal.pc

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] = {
    val dealiased = tpe.dealiasWiden
    if (definitions.isFunctionTypeDirect(dealiased)) dealiased.typeArgs.init
    else Nil
  }
}
