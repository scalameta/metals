package scala.meta.internal.pc

import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.reporters.StoreReporter

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] =
    definitions.functionOrSamArgTypes(tpe)

  def storeReporter(r: Reporter): Option[StoreReporter] =
    r match {
      case s: StoreReporter => Some(s)
      case _ => None
    }
}
