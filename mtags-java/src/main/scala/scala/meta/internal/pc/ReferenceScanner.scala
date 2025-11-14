package scala.meta.internal.pc

import javax.lang.model.element.Element

import scala.collection.mutable.ListBuffer

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Range

abstract class ReferenceScanner[T](
    targetElement: Element => Boolean,
    trees: Trees,
    root: CompilationUnitTree,
    text: String,
    compiler: JavaMetalsGlobal,
    includeDefinition: Boolean
) extends TreePathScanner[Void, Void] {
  private val elementBuffer: ListBuffer[T] = new ListBuffer[T]

  def result(): List[T] = elementBuffer.toList

  protected def createElement(range: Range, isDefinition: Boolean): T
  override def scan(tree: Tree, p: Void): Void = {
    if (tree != null) {
      val treePath = new TreePath(getCurrentPath, tree)
      val element = trees.getElement(treePath)

      if (element != null && targetElement(element)) {
        val sourcePositions = trees.getSourcePositions()
        val treeStart = sourcePositions.getStartPosition(root, tree)
        val treeEnd = sourcePositions.getEndPosition(root, tree)
        if (treeStart >= 0 && treeEnd >= 0) {
          // Extract just the name position from the full tree range
          val elementName = element.getSimpleName().toString()

          // Find the name as a complete identifier, not as a substring
          val (start, end) = compiler.findIndentifierStartAndEnd(
            text,
            elementName,
            treeStart.toInt,
            treeEnd.toInt,
            tree,
            root,
            sourcePositions
          )

          val isAtDefinition = isDefinition(tree)
          // We will already us the identifier in this case, so it would be duplicated
          def isNotInvocation = tree.getKind() != Tree.Kind.METHOD_INVOCATION

          if (
            isNotInvocation && (isAtDefinition && includeDefinition || !isAtDefinition)
          ) {
            elementBuffer += createElement(
              new Range(
                compiler.offsetToPosition(start, text),
                compiler.offsetToPosition(end, text)
              ),
              isAtDefinition
            )
          }
        }
      }
    }
    super.scan(tree, p)
  }

  private def isDefinition(tree: Tree): Boolean = {
    import com.sun.source.tree.Tree.Kind._
    tree.getKind match {
      case VARIABLE | METHOD | CLASS | INTERFACE | ENUM | ANNOTATION_TYPE =>
        true
      case _ => false
    }
  }

}
