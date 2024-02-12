package scala.meta.internal.pc

import scala.reflect.internal.Reporter
import scala.tools.nsc.reporters.StoreReporter
import scala.meta.pc.VirtualFileParams

trait Compat { this: MetalsGlobal =>
  def metalsFunctionArgTypes(tpe: Type): List[Type] =
    definitions.functionOrPfOrSamArgTypes(tpe)

  val AssignOrNamedArg: NamedArgExtractor = NamedArg

  def storeReporter(r: Reporter): Option[StoreReporter] =
    r match {
      case s: StoreReporter => Some(s)
      case _ => None
    }

  def isAliasCompletion(m: Member): Boolean = m match {
    case tm: TypeMember => tm.aliasInfo.nonEmpty
    case sm: ScopeMember => sm.aliasInfo.nonEmpty
    case _ => false
  }

  def constantType(c: ConstantType): ConstantType =
    if (c.value.isSuitableLiteralType) LiteralType(c.value) else c

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
