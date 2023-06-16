package scala.meta.internal.pc

import scala.reflect.internal.Reporter
import scala.tools.nsc.reporters.StoreReporter

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] =
    definitions.functionOrPfOrSamArgTypes(tpe)

  val AssignOrNamedArg: NamedArgExtractor = NamedArg

  def storeReporter(r: Reporter): Option[StoreReporter] =
    r match {
      case s: StoreReporter => Some(s)
      case _ => None
    }

  def isAliasCompletion(m: Member): Boolean = false

  def constantType(c: ConstantType): ConstantType =
    if (c.value.isSuitableLiteralType) LiteralType(c.value) else c
}
