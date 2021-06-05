package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta.internal.mtags.MtagsEnrichments._

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Denotations._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._

case class NamesInScope(
    values: Map[String, Symbol]
) {

  def lookupSym(sym: Symbol)(using Context): NamesInScope.Result = {
    values.get(sym.decodedName) match {
      case Some(existing) if sameSymbol(existing, sym) =>
        NamesInScope.Result.InScope
      case Some(_) => NamesInScope.Result.Conflict
      case None => NamesInScope.Result.Missing
    }
  }

  def symbolByName(name: String): Option[Symbol] =
    values.get(name)

  def scopeSymbols: List[Symbol] = values.values.toList

  private def sameSymbol(s1: Symbol, s2: Symbol)(using Context): Boolean = {
    s1 == s2 || s1.fullNameBackticked == s2.fullNameBackticked
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

  /**
   * The implementation hints are taken from dotty:
   *  https://github.com/lampepfl/dotty/blob/3ff472f42a911390e0ac31b7095079d7bd9c839c/compiler/src/dotty/tools/dotc/interactive/Completion.scala#L169
   *
   * Scope symbols might be obtained from current tree and by inspecting `ctx.importInfo`
   * The important difference with original implementation is that we had to process `Import`
   * stats from exising Tree(`inspectImports`).
   * In some cases when source is incomplete `ctx.importInfo` doesn't reflect
   * all exising imports (only virtual one like: `scala._`, `scala.Predef._`, `java.lang._`).
   */
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

    val all = (fromTree ++ fromImports).map { sym =>
      (sym.decodedName, sym)
    } ++ inspectImports(tree)
    NamesInScope(all.toMap)
  }

  private def inspectImports(
      tree: Tree
  )(using Context): List[(String, Symbol)] = {
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
              .map(_.decodedName)
              .toSet

            val syms = selectors.flatMap { selector =>
              if (selector.isWildcard)
                select.info.allMembers
                  .map(_.symbol)
                  .map(s => (s.decodedName, s))
              else
                val sym = select.info.member(selector.name).symbol
                val name = selector.renamed match {
                  case Ident(name: TermName) => name.decoded
                  case _ => selector.imported.name.decoded
                }
                List((name -> sym))
            }

            val filtered = syms.filter((_, sym) => {
              sym != NoSymbol && sym.isPublic && !excluded
                .contains(sym.decodedName)
            })
            filtered
          }
          .flatten
      }
      .getOrElse(Nil)
  }

}
