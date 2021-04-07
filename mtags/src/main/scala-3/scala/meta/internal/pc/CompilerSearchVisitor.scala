package scala.meta.internal.pc

import scala.meta.pc._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Symbols._

class CompilerSearchVisitor(
    query: String,
    visitSymbol: Symbol => Boolean
)(using ctx: Context)
    extends SymbolSearchVisitor {

  def isAccessible(sym: Symbol): Boolean = {
    sym != NoSymbol && sym.isPublic
  }

  def toSymbol(
      owner: String,
      parts: List[String],
      prev: Option[Symbol]
  ): Option[Symbol] = {
    parts match {
      case x :: xs =>
        val clzName = s"$owner.$x"
        val sym = requiredClass(clzName)
        if (isAccessible(sym)) toSymbol(clzName, xs, Some(sym))
        else None
      case Nil => prev
    }
  }

  def visitClassfile(pkgPath: String, filename: String): Int = {
    val pkg = normalizePackage(pkgPath)

    val innerPath = filename
      .stripSuffix(".class")
      .stripSuffix("$")
      .split("$")

    toSymbol(pkg, innerPath.toList, None)
      .filter(visitSymbol)
      .map(_ => 1)
      .getOrElse(0)
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
