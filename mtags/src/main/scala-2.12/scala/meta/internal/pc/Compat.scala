package scala.meta.internal.pc

import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.reporters.StoreReporter
import scala.meta.pc.VirtualFileParams

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] =
    definitions.functionOrSamArgTypes(tpe)

  def storeReporter(r: Reporter): Option[StoreReporter] =
    r match {
      case s: StoreReporter => Some(s)
      case _ => None
    }

  def isAliasCompletion(m: Member): Boolean = false

  def constantType(c: ConstantType): ConstantType = c

  def runOutline(files: List[VirtualFileParams]): Unit = {
    this.settings.Youtline.value = true
    files.foreach { params =>
      val unit = this.addCompilationUnit(
        params.text(),
        params.uri.toString(),
        cursor = None,
        isOutline = true
      )
      this.typeCheck(unit)
      this.richCompilationCache.put(params.uri().toString(), unit)
    }
    this.settings.Youtline.value = false
  }
}
