package scala.meta.internal.pc

import javax.lang.model.element.Element

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Range

class JavaDocumentHighlightProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams
) {

  def documentHighlight(): List[DocumentHighlight] = params match {
    case range: RangeParams =>
      range.trimWhitespaceInRange.map(documentHighlight).getOrElse(Nil)
    case _ if isWhitespace => Nil
    case _ => documentHighlight(params)
  }

  private def isWhitespace: Boolean = {
    params.offset() < 0 ||
    params.offset() >= params.text().length ||
    params.text().charAt(params.offset()).isWhitespace
  }

  private def documentHighlight(
      params: OffsetParams
  ): List[DocumentHighlight] = {
    val task: JavacTask =
      compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val trees = Trees.instance(task)
    val position = compiler.positionFromParams(params)
    val node = compiler.compilerTreeNode(scanner, position)

    def isAtIdentifier(treePath: TreePath, element: Element): Boolean = {
      val leaf = treePath.getLeaf()
      val sourcePositions = trees.getSourcePositions()
      val treeStart = sourcePositions.getStartPosition(scanner.root, leaf)
      val treeEnd = sourcePositions.getEndPosition(scanner.root, leaf)
      if (treeStart >= 0 && treeEnd >= 0) {
        val elementName = element.getSimpleName().toString()
        val (start, end) = compiler.findIndentifierStartAndEnd(
          params.text(),
          elementName,
          treeStart.toInt,
          treeEnd.toInt,
          leaf,
          scanner.root,
          sourcePositions
        )
        start <= params.offset() && end >= params.offset()
      } else false
    }

    node match {
      case Some(treePath) =>
        val element = trees.getElement(treePath)

        if (element != null && isAtIdentifier(treePath, element)) {
          findAllReferences(scanner.root, element, trees, params.text())
        } else {
          Nil
        }
      case None => Nil
    }
  }

  private def findAllReferences(
      root: CompilationUnitTree,
      targetElement: Element,
      trees: Trees,
      text: String
  ): List[DocumentHighlight] = {
    val scanner = new DocumentHighlightScanner(targetElement, trees, root, text)
    scanner.scan(root, null)
    scanner.result().reverse.distinctBy(_.getRange().toString())
  }

  private class DocumentHighlightScanner(
      targetElement: Element,
      trees: Trees,
      root: CompilationUnitTree,
      text: String
  ) extends ReferenceScanner[DocumentHighlight](
        element => element.equals(targetElement),
        trees,
        root,
        text,
        compiler,
        includeDefinition = true
      ) {
    override protected def createElement(
        range: Range,
        isDefinition: Boolean
    ): DocumentHighlight = {
      new DocumentHighlight(
        range,
        if (isDefinition) DocumentHighlightKind.Write
        else DocumentHighlightKind.Read
      )
    }
  }
}
