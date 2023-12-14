package scala.meta.internal.pc

import scala.util.control.NonFatal

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*

class ParentProvider(using Context):
  private def toSymbols(
      pkg: String,
      parts: List[(String, Boolean)],
  ): Option[Symbol] =
    def loop(owner: Symbol, parts: List[(String, Boolean)]): Option[Symbol] =
      parts match
        case (head, isClass) :: tl =>
          val next =
            if isClass then owner.info.member(typeName(head))
            else owner.info.member(termName(head))
          if next.exists then loop(next.symbol, tl)
          else None
        case Nil => Some(owner)

    val pkgSym = requiredPackage(pkg)
    loop(pkgSym, parts)
  end toSymbols

  def parents(symbol: String): List[String] =
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
      loop(symbol.drop(index + 1), List.empty)

    val foundSym =
      try toSymbols(pkg, names)
      catch case NonFatal(e) => None
    foundSym.toList.flatMap(_.ownersIterator).map(SemanticdbSymbols.symbolName)
  end parents

  private def normalizePackage(pkg: String): String =
    pkg.replace("/", ".").stripSuffix(".")
end ParentProvider
