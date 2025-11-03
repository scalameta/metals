package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.{util => ju}

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

class SimpleCollectingSymbolSearchVisitor extends SymbolSearchVisitor {
  var results = new ju.concurrent.ConcurrentLinkedQueue[l.SymbolInformation]()
  def shouldVisitPackage(pkg: String): Boolean = true
  def visitClassfile(pkg: String, filename: String): Int =
    throw new UnsupportedOperationException
  def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: l.SymbolKind,
      range: l.Range,
  ): Int = {
    val sym = Symbol(symbol)
    results.add(
      new l.SymbolInformation(
        sym.displayName,
        kind,
        new l.Location(path.toURI.toString(), range),
        sym.owner.value.replace('/', '.'),
      )
    )
    1
  }
  def isCancelled(): Boolean = false
}
