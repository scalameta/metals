package scala.meta.internal.pc

import java.nio.file.Path
import scala.meta.pc.SymbolSearchVisitor
import org.eclipse.{lsp4j => l}

class CompilerSearchVisitor(
    query: String,
    containsPackage: String => Boolean,
    visit: SymbolSearchCandidate => Int
) extends SymbolSearchVisitor {
  def visitClassfile(pkg: String, filename: String): Int = {
    visit(SymbolSearchCandidate.Classfile(pkg, filename))
  }
  def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: l.SymbolKind,
      range: l.Range
  ): Int = {
    visit(SymbolSearchCandidate.Workspace(symbol))
  }

  def shouldVisitPackage(pkg: String): Boolean = containsPackage(pkg)

  override def isCancelled: Boolean = {
    // TODO(olafur) integrate CancelChecker
    false
  }
}
