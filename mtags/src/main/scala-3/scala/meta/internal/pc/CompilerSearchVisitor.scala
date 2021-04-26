package scala.meta.internal.pc

import scala.meta.pc._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.SymDenotations._

class CompilerSearchVisitor(
    query: String,
    visitSymbol: Symbol => Boolean
)(using ctx: Context)
    extends SymbolSearchVisitor {

  private def isAccessible(sym: Symbol): Boolean = {
    sym != NoSymbol && sym.isPublic
  }

  private def toSymbols(
      pkg: String,
      parts: List[String]
  ): List[Symbol] = {
    def loop(owners: List[Symbol], parts: List[String]): List[Symbol] = {
      parts match {
        case head :: tl =>
          val next = owners.flatMap { sym =>
            val term = sym.info.member(termName(head))
            val tpe = sym.info.member(typeName(head))

            List(term, tpe)
              .filter(denot => denot.exists)
              .map(_.symbol)
              .filter(isAccessible)
          }
          loop(next, tl)
        case Nil => owners
      }
    }

    val pkgSym = requiredPackage(pkg)
    loop(List(pkgSym), parts)
  }

  def visitClassfile(pkgPath: String, filename: String): Int = {
    val pkg = normalizePackage(pkgPath)

    val innerPath = filename
      .stripSuffix(".class")
      .stripSuffix("$")
      .split("\\$")

    val added = toSymbols(pkg, innerPath.toList).filter(visitSymbol)
    added.size
  }

  def visitWorkspaceSymbol(
      path: java.nio.file.Path,
      symbol: String,
      kind: org.eclipse.lsp4j.SymbolKind,
      range: org.eclipse.lsp4j.Range
  ): Int = {
    val gsym = SemanticdbSymbols.inverseSemanticdbSymbol(symbol).headOption
    gsym
      .filter(isAccessible)
      .map(visitSymbol)
      .map(_ => 1)
      .getOrElse(0)
  }

  def shouldVisitPackage(pkg: String): Boolean =
    isAccessible(requiredPackage(normalizePackage(pkg)))

  override def isCancelled: Boolean = false

  private def normalizePackage(pkg: String): String =
    pkg.replace("/", ".").stripSuffix(".")
}
