package scala.meta.internal.pc

import java.{util => ju}

import scala.reflect.internal.Reporter
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.OutlineFiles
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
  def createStoreReporter(settings: Settings): StoreReporter =
    new StoreReporter(settings)

  def isAliasCompletion(m: Member): Boolean = m match {
    case tm: TypeMember => tm.aliasInfo.nonEmpty
    case sm: ScopeMember => sm.aliasInfo.nonEmpty
    case _ => false
  }

  def constantType(c: ConstantType): ConstantType =
    if (c.value.isSuitableLiteralType) LiteralType(c.value) else c

  def runOutline(files: OutlineFiles): Unit = {
    this.settings.Youtline.value = true
    runOutline(files.files)
    if (files.isFirstCompileSubstitute()) {
      // if first compilation substitute we compile all files twice
      // first to emit symbols, second so signatures have information about those symbols
      // this isn't a perfect strategy but much better than single compile
      runOutline(files.files, forceNewUnit = true)
    }
    this.settings.Youtline.value = false
  }

  /**
   * Force opening package module to make sure we have access to all symbols.
   * Connected to https://github.com/scala/bug/issues/12663
   *
   * The issue that were solved by making this lazy very mostly related to zinc
   * so this should not be an issue here.
   */
  override def openPackageModule(pkgClass: Symbol, force: Boolean): Unit = {
    val pkgModule = pkgClass.packageObject
    def fromSource = pkgModule.rawInfo match {
      case ltp: SymLoader => ltp.fromSource
      case _ => false
    }
    if (pkgModule.isModule && !fromSource)
      openPackageModule(pkgModule, pkgClass)
  }

  private def runOutline(
      files: ju.List[VirtualFileParams],
      forceNewUnit: Boolean = false
  ): Unit = {
    files.asScala.foreach { params =>
      val unit = this.addCompilationUnit(
        params.text(),
        params.uri.toString(),
        cursor = None,
        isOutline = true,
        forceNew = forceNewUnit
      )
      this.typeCheck(unit)
      this.richCompilationCache.put(params.uri().toString(), unit)
    }
  }
}
