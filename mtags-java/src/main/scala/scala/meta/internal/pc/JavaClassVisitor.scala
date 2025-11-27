package scala.meta.internal.pc

import java.nio.file.Path
import javax.lang.model.element.Element
import javax.lang.model.util.Elements

import scala.jdk.CollectionConverters._

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

sealed trait ScopeElement {
  def element: Element
}
case class SimpleElement(element: Element) extends ScopeElement

case class ImportableElement(element: Element) extends ScopeElement

class JavaClassVisitor(elements: Elements, visitMember: Element => Boolean)
    extends SymbolSearchVisitor {
  private def toDotPackage(pkg: String) =
    pkg.replace("/", ".").stripSuffix(".")
  override def shouldVisitPackage(pkg: String): Boolean = {
    elements.getPackageElement(toDotPackage(pkg)) != null
  }

  override def visitClassfile(pkg: String, filename: String): Int = {
    val pkgElem = elements.getPackageElement(toDotPackage(pkg))
    if (pkgElem == null) return 0

    val className = filename.stripSuffix(".class")
    val parts = className.split('$')
    val topLevelName = parts.head

    val current: Element = pkgElem
      .getEnclosedElements()
      .asScala
      .find { cls =>
        cls.getSimpleName().toString == topLevelName
      }
      .orNull

    if (current == null) return 0

    // Traverse inner classes
    def visit(element: Element): Int = {
      val selfCount = if (visitMember(element)) 1 else 0
      val enclosed = element.getEnclosedElements.asScala
      val innerCount = enclosed
        .filter(e => e.getKind.isClass || e.getKind.isInterface)
        .map(e => visit(e))
        .sum
      selfCount + innerCount
    }

    if (current != null) visit(current) else 0
  }

  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: Range
  ): Int = {
    // pprint.log(path)
    0
  }

  override def isCancelled(): Boolean = false

}
