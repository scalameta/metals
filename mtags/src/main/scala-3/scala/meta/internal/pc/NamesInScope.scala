package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Denotations._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.ast.tpd._
import scala.annotation.tailrec

case class NamesInScope(
    values: Map[String, Symbol]
) {

  def lookupSym(sym: Symbol)(using Context): NamesInScope.Result = {
    values.get(sym.showName) match {
      case Some(existing) if sameSymbol(existing, sym) =>
        NamesInScope.Result.InScope
      case Some(_) => NamesInScope.Result.Conflict
      case None => NamesInScope.Result.Missing
    }
  }

  def scopeSymbols: List[Symbol] = values.values.toList

  private def sameSymbol(s1: Symbol, s2: Symbol)(using Context): Boolean = {
    s1 == s2 || s1.showFullName == s2.showFullName
  }

}

object NamesInScope {

  enum Result {
    case InScope, Conflict, Missing
    def exists: Boolean = this match {
      case InScope | Conflict => true
      case Missing => false
    }
  }

  def build(tree: Tree)(using ctx: Context): NamesInScope = {

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

    def fromImport(site: Type, name: Name): List[Symbol] = {
      site.member(name).alternatives.map(_.symbol)
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
            val fromWildCard =
              if (imp.isWildcardImport) {
                allAccessibleSymbols(
                  imp.site,
                  sym => !imp.excluded.contains(sym.name.toTermName)
                )
              } else Nil
            val explicit =
              imp.forwardMapping.toList
                .map(_._2)
                .filter(name => !imp.excluded.contains(name))
                .flatMap(fromImport(imp.site, _))
            fromWildCard ++ explicit
          case None =>
            List.empty
        }
      }

    val all = fromTree ++ fromImports ++ inspectImports(tree)
    val values = all.map { sym => (sym.showName, sym) }.toMap
    NamesInScope(values)
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
