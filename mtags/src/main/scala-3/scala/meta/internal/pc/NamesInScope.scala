package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Denotations._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.ast.tpd._

object NamesInScope {

  def lookup(tree: Tree)(using ctx: Context): Map[String, Symbol] = {

    def accessibleSymbols(site: Type, tpe: Type): List[Symbol] = {
      tpe.decls.toList.filter(sym =>
        sym.isAccessibleFrom(site, superAccess = false)
      )
    }

    def allAccessibleSymbols(
        tpe: Type,
        filter: Symbol => Boolean
    ): List[Symbol] = {
      val initial = accessibleSymbols(tpe, tpe).filter(filter)
      val fromPackageObjects =
        initial
          .filter(_.isPackageObject)
          .flatMap(sym => accessibleSymbols(tpe, sym.thisType))
      initial ++ fromPackageObjects
    }

    val fromTree =
      tree.typeOpt match {
        case site: NamedType if site.symbol.is(Package) =>
          allAccessibleSymbols(site, _ => true)
        case x =>
          List.empty
      }

    val fromImports =
      ctx.outersIterator.toList.flatMap { ctx =>
        Option(ctx.importInfo) match {
          case Some(imp) =>
            allAccessibleSymbols(
              imp.site,
              sym => !imp.excluded.contains(sym.name.toTermName)
            )
          case None => List.empty
        }
      }

    (fromTree ++ fromImports).map { sym => (sym.showName, sym) }.toMap
  }
}
