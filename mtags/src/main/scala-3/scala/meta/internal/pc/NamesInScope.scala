package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Denotations._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.interactive.Completion.Completer
import dotty.tools.dotc.interactive.Completion.Mode
import scala.util.control.NonFatal
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.annotation.tailrec

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
        case _ =>
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
          case None =>
            List.empty
        }
      }

    val all = fromTree ++ fromImports ++ inspectImports(tree)
    all.map { sym => (sym.showName, sym) }.toMap
  }

  /**
   * @dos65 In theory all import names should be inspected using `ctx.importInfo`.
   * However, in case if source can't be compiled `ctx.importInfo` contains only default imports from Predef.
   */
  private def inspectImports(tree: Tree)(using Context): List[Symbol] = {
    @tailrec
    def lastPackageDef(
        prev: Option[PackageDef],
        tree: Tree
    ): Option[PackageDef] = {
      tree match {
        case curr @ PackageDef(_, (next: PackageDef) :: Nil)
            if !curr.symbol.isPackageObject =>
          lastPackageDef(Some(curr), next)
        case pkg: PackageDef if !pkg.symbol.isPackageObject => Some(pkg)
        case _ => prev
      }
    }

    lastPackageDef(None, tree)
      .map { pkg =>
        pkg.stats
          .takeWhile(_.isInstanceOf[Import])
          .collect { case i @ Import(expr, selectors) =>
            val select = expr.symbol
            val excluded = selectors
              .flatMap { selector =>
                selector.renamed match {
                  case Ident(name) if name == nme.WILDCARD =>
                    select.info.member(selector.name).alternatives.map(_.symbol)
                  case _ => Nil
                }
              }
              .map(_.showName)
              .toSet

            val syms = selectors.flatMap { selector =>
              if (selector.isWildcard) select.info.allMembers.map(_.symbol)
              else
                val sym = select.info.member(selector.rename).symbol
                List(sym)
            }

            val filtered = syms.filter(sym => {
              sym != NoSymbol && sym.isPublic && !excluded
                .contains(sym.showName)
            })
            filtered
          }
          .flatten
      }
      .getOrElse(Nil)
  }

}
