package scala.meta.internal.jpc

import java.nio.file.Path

import scala.collection.mutable.ArrayBuffer

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j
import org.eclipse.lsp4j.SymbolKind

class JavaCompletionSearchVisitor extends SymbolSearchVisitor {
  val visitedFQN: ArrayBuffer[String] = ArrayBuffer.empty[String]
  override def shouldVisitPackage(pkg: String): Boolean = true
  override def isCancelled(): Boolean = false
  override def visitClassfile(pkg: String, filename: String): Int = {
    if (filename.contains('$')) {
      // TODO: handle inner classes
      return 0
    }
    visitedFQN += s"${pkg.replace('/', '.')}${filename.stripSuffix(".class")}"
    1
  }

  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: lsp4j.Range
  ): Int = {
    if (!symbol.endsWith("#")) {
      // Don't autocomplete non-type values like Scala objects. We may want to
      // have special support for them in the future.
      return 0
    }
    val visited = symbol.stripSuffix("#").replace('/', '.')
    visitedFQN += visited
    1
  }
}
