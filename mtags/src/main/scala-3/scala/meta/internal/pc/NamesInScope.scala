package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments._

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Denotations._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.SymDenotations._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.typer.ImportInfo

case class NamesInScope(
    values: Map[String, Symbol],
    renames: Map[SimpleName, String]
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

  def hasRename(sym: Symbol, rename: String)(using Context): Boolean =
    renames.get(sym.name.toSimpleName) match {
      case Some(v) => v == rename
      case None => false
    }

  private def sameSymbol(s1: Symbol, s2: Symbol)(using Context): Boolean = {
    s1 == s2 ||
    s1.fullNameBackticked == s2.fullNameBackticked && s1.owner == s2.owner
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
   */
  def build(ctx: Context): NamesInScope = {

    def accessibleSymbols(site: Type, tpe: Type)(using
        Context
    ): List[Symbol] = {
      tpe.decls.toList.filter(sym =>
        sym.isAccessibleFrom(site, superAccess = false)
      )
    }

    def accesibleMembers(site: Type)(using Context): List[Symbol] =
      site.allMembers
        .filter(denot =>
          denot.symbol.isAccessibleFrom(site, superAccess = false)
        )
        .map(_.symbol)
        .toList

    def allAccessibleSymbols(
        tpe: Type,
        filter: Symbol => Boolean = _ => true
    )(using Context): List[Symbol] = {
      val initial = accessibleSymbols(tpe, tpe).filter(filter)
      val fromPackageObjects =
        initial
          .filter(_.isPackageObject)
          .flatMap(sym => accessibleSymbols(tpe, sym.thisType))
      initial ++ fromPackageObjects
    }

    def fromImport(site: Type, name: Name)(using Context): List[Symbol] = {
      site.member(name).alternatives.map(_.symbol)
    }

    def fromImportInfo(
        imp: ImportInfo
    )(using Context): List[(Symbol, Option[TermName])] = {
      val excludedNames = imp.excluded.map(_.decoded)

      if (imp.isWildcardImport) {
        allAccessibleSymbols(
          imp.site,
          sym => !excludedNames.contains(sym.name.decoded)
        ).map((_, None))
      } else {
        imp.forwardMapping.toList.flatMap { (name, rename) =>
          val isRename = name != rename
          if (!isRename && !excludedNames.contains(name.decoded))
            fromImport(imp.site, name).map((_, None))
          else if (isRename)
            fromImport(imp.site, name).map((_, Some(rename)))
          else Nil
        }
      }
    }

    val (symbols, renames) =
      ctx.outersIterator.toList.reverse
        .foldLeft((List.empty[Symbol], Map.empty[SimpleName, String])) {
          case ((acc, renamesAcc), ctx) =>
            given Context = ctx
            if (ctx.isImportContext)
              val (syms, renames) =
                fromImportInfo(ctx.importInfo)
                  .map((sym, rename) =>
                    (sym, rename.map(r => sym.name.toSimpleName -> r.show))
                  )
                  .unzip
              val nextSymbols = (acc ++ syms)
              val nextRenames = renamesAcc ++ renames.flatten.toMap
              (nextSymbols, nextRenames)
            else if (ctx.owner.isClass) {
              val site = ctx.owner.thisType
              (acc ++ accesibleMembers(site), renamesAcc)
            } else if (ctx.scope != null) {
              (acc ++ ctx.scope.toList, renamesAcc)
            } else (acc, renamesAcc)
        }

    val values = symbols.map(s => s.decodedName(using ctx) -> s).toMap
    NamesInScope(values, renames)
  }

}
