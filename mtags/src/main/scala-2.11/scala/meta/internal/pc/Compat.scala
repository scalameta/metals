package scala.meta.internal.pc

import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.reporters.StoreReporter

import scala.meta.pc.OutlineFiles

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] = {
    val dealiased = tpe.dealiasWiden
    if (definitions.isFunctionTypeDirect(dealiased)) dealiased.typeArgs.init
    else Nil
  }

  def storeReporter(r: Reporter): Option[StoreReporter] =
    r match {
      case s: StoreReporter => Some(s)
      case _ => None
    }

  def isAliasCompletion(m: Member): Boolean = false

  def constantType(c: ConstantType): ConstantType = c

  def runOutline(files: OutlineFiles): Unit = {
    // no outline compilation for 2.11
  }
}
