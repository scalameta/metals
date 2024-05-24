package scala.meta.internal.pc

import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments.metalsDealias
import scala.meta.pc.PcSymbolKind
import scala.meta.pc.PcSymbolProperty

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Denotations.MultiDenotation
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*

class SymbolInformationProvider(using Context):

  def info(symbol: String): Option[PcSymbolInformation] =
    val foundSymbols = SymbolProvider.compilerSymbols(symbol)

    val (searchedSymbol, alternativeSymbols) =
      foundSymbols.partition(compilerSymbol =>
        SemanticdbSymbols.symbolName(compilerSymbol) == symbol
      )

    searchedSymbol match
      case Nil => None
      case sym :: _ =>
        val classSym = if sym.isClass then sym else sym.moduleClass
        val parents =
          if classSym.isClass
          then classSym.asClass.parentSyms.map(SemanticdbSymbols.symbolName)
          else Nil
        val dealisedSymbol =
          if sym.isAliasType then sym.info.metalsDealias.typeSymbol else sym
        val classOwner =
          sym.ownersIterator.drop(1).find(s => s.isClass || s.is(Flags.Module))
        val overridden = sym.denot.allOverriddenSymbols.toList

        val pcSymbolInformation =
          PcSymbolInformation(
            symbol = SemanticdbSymbols.symbolName(sym),
            kind = getSymbolKind(sym),
            parents = parents,
            dealiasedSymbol = SemanticdbSymbols.symbolName(dealisedSymbol),
            classOwner = classOwner.map(SemanticdbSymbols.symbolName),
            overriddenSymbols = overridden.map(SemanticdbSymbols.symbolName),
            alternativeSymbols =
              alternativeSymbols.map(SemanticdbSymbols.symbolName),
            properties =
              if sym.is(Flags.Abstract) then List(PcSymbolProperty.ABSTRACT)
              else Nil,
          )

        Some(pcSymbolInformation)
    end match
  end info

  private def getSymbolKind(sym: Symbol): PcSymbolKind =
    if sym.isAllOf(Flags.JavaInterface) then PcSymbolKind.INTERFACE
    else if sym.is(Flags.Trait) then PcSymbolKind.TRAIT
    else if sym.isConstructor then PcSymbolKind.CONSTRUCTOR
    else if sym.isPackageObject then PcSymbolKind.PACKAGE_OBJECT
    else if sym.isClass then PcSymbolKind.CLASS
    else if sym.is(Flags.Macro) then PcSymbolKind.MACRO
    else if sym.is(Flags.Local) then PcSymbolKind.LOCAL
    else if sym.is(Flags.Method) then PcSymbolKind.METHOD
    else if sym.is(Flags.Param) then PcSymbolKind.PARAMETER
    else if sym.is(Flags.Package) then PcSymbolKind.PACKAGE
    else if sym.is(Flags.TypeParam) then PcSymbolKind.TYPE_PARAMETER
    else if sym.isType then PcSymbolKind.TYPE
    else PcSymbolKind.UNKNOWN_KIND
end SymbolInformationProvider

object SymbolProvider:

  def compilerSymbol(symbol: String)(using Context): Option[Symbol] =
    compilerSymbols(symbol).find(sym => SemanticdbSymbols.symbolName(sym) == symbol)

  def compilerSymbols(symbol: String)(using Context): List[Symbol] =
    val index = symbol.lastIndexOf("/")
    val pkg = normalizePackage(symbol.take(index + 1))

    def loop(
        symbol: String,
        acc: List[(String, Boolean)],
    ): List[(String, Boolean)] =
      if symbol.isEmpty() then acc.reverse
      else
        val newSymbol = symbol.takeWhile(c => c != '.' && c != '#')
        val rest = symbol.drop(newSymbol.size)
        loop(rest.drop(1), (newSymbol, rest.headOption.exists(_ == '#')) :: acc)
    val names =
      loop(symbol.drop(index + 1).takeWhile(_ != '('), List.empty)

    try toSymbols(pkg, names)
    catch case NonFatal(e) => Nil

  private def normalizePackage(pkg: String): String =
    pkg.replace("/", ".").stripSuffix(".")

  private def toSymbols(
      pkg: String,
      parts: List[(String, Boolean)],
  )(using Context): List[Symbol] =
    def collectSymbols(denotation: Denotation): List[Symbol] =
      denotation match
        case MultiDenotation(denot1, denot2) =>
          collectSymbols(denot1) ++ collectSymbols(denot2)
        case denot => List(denot.symbol)

    def loop(
        owners: List[Symbol],
        parts: List[(String, Boolean)],
    ): List[Symbol] =
      parts match
        case (head, isClass) :: tl =>
          val foundSymbols =
            owners.flatMap { owner =>
              val next =
                if isClass then owner.info.member(typeName(head))
                else owner.info.member(termName(head))
              collectSymbols(next).filter(_.exists)
            }
          if foundSymbols.nonEmpty then loop(foundSymbols, tl)
          else Nil
        case Nil => owners

    val pkgSym =
      if pkg == "_empty_" then requiredPackage(nme.EMPTY_PACKAGE)
      else requiredPackage(pkg)
    loop(List(pkgSym), parts)
  end toSymbols
