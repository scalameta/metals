package scala.meta.internal.pc

import javax.lang.model.element.Element

import scala.jdk.CollectionConverters.SeqHasAsJava

import scala.meta.pc.OffsetParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range

class JavaReferencesProvider(
    compiler: JavaMetalsGlobal,
    params: ReferencesRequest
) {

  def references(): List[ReferencesResult] = {
    params.offsetOrSymbol() match {
      case either if either.isLeft() =>
        val offsetParams =
          new OffsetParams {
            def uri() = params.file().uri()
            def text() = params.file().text()
            def offset() = either.getLeft()
            def token() = params.file().token()
          }
        if (!isWhitespace(offsetParams)) {
          val refs = findReferences(offsetParams)
          if (refs.locations.isEmpty()) Nil
          else List(refs)
        } else {
          Nil
        }
      case either if either.isRight() =>
        val symbol = either.getRight()
        val refs = findReferences(symbol, params)
        if (refs.locations.isEmpty()) Nil
        else List(refs)
      case _ =>
        Nil
    }
  }

  private def isWhitespace(params: OffsetParams): Boolean = {
    params.offset() < 0 ||
    params.offset() >= params.text().length ||
    params.text().charAt(params.offset()).isWhitespace
  }

  private def findReferences(
      symbol: String,
      params: ReferencesRequest
  ): PcReferencesResult = {
    val task: JavacTask =
      compiler.compilationTask(params.file().text(), params.file().uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val trees = Trees.instance(task)
    val symbolScanner = new SymbolReferencesScanner(
      symbol,
      trees,
      scanner.root,
      params.file().text(),
      params.file().uri().toString(),
      params.includeDefinition()
    )
    symbolScanner.scan(scanner.root, null)
    PcReferencesResult(
      symbol,
      symbolScanner
        .result()
        .reverse
        .distinctBy(_.getRange().toString())
        .asJava
    )
  }

  private def findReferences(
      offsetParams: OffsetParams
  ): PcReferencesResult = {
    val task: JavacTask =
      compiler.compilationTask(offsetParams.text(), offsetParams.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val trees = Trees.instance(task)
    val position = compiler.positionFromParams(offsetParams)
    val node = compiler.compilerTreeNode(scanner, position)

    def isAtIdentifier(treePath: TreePath, element: Element): Boolean = {
      val leaf = treePath.getLeaf()
      val sourcePositions = trees.getSourcePositions()
      val treeStart = sourcePositions.getStartPosition(scanner.root, leaf)
      val treeEnd = sourcePositions.getEndPosition(scanner.root, leaf)
      if (treeStart >= 0 && treeEnd >= 0) {
        val elementName = element.getSimpleName().toString()
        val (start, end) = compiler.findIndentifierStartAndEnd(
          offsetParams.text(),
          elementName,
          treeStart.toInt,
          treeEnd.toInt,
          leaf,
          scanner.root,
          sourcePositions
        )
        start <= offsetParams.offset() && end >= offsetParams.offset()
      } else false
    }

    node match {
      case Some(treePath) =>
        val element = trees.getElement(treePath)

        if (element != null && isAtIdentifier(treePath, element)) {
          findAllReferences(
            scanner.root,
            element,
            trees,
            offsetParams.text(),
            offsetParams.uri().toString()
          )
        } else {
          PcReferencesResult.empty.asInstanceOf[PcReferencesResult]
        }
      case None => PcReferencesResult.empty.asInstanceOf[PcReferencesResult]
    }
  }

  private def findAllReferences(
      root: CompilationUnitTree,
      targetElement: Element,
      trees: Trees,
      text: String,
      uri: String
  ): PcReferencesResult = {
    val scanner =
      new ReferencesScanner(
        targetElement,
        trees,
        root,
        text,
        uri,
        params.includeDefinition()
      )
    scanner.scan(root, null)

    val symbol = compiler.semanticdbSymbol(targetElement)
    PcReferencesResult(
      symbol,
      scanner.result().reverse.distinctBy(_.getRange().toString()).asJava
    )
  }

  private class ReferencesScanner(
      targetElement: Element,
      trees: Trees,
      root: CompilationUnitTree,
      text: String,
      uri: String,
      includeDefinition: Boolean
  ) extends ReferenceScanner[Location](
        element => element.equals(targetElement),
        trees,
        root,
        text,
        compiler,
        includeDefinition
      ) {
    override protected def createElement(
        range: Range,
        isDefinition: Boolean
    ): Location = {
      new Location(
        uri,
        range
      )
    }
  }
  private class SymbolReferencesScanner(
      targetElement: String,
      trees: Trees,
      root: CompilationUnitTree,
      text: String,
      uri: String,
      includeDefinition: Boolean
  ) extends ReferenceScanner[Location](
        element => compiler.semanticdbSymbol(element).equals(targetElement),
        trees,
        root,
        text,
        compiler,
        includeDefinition
      ) {
    override protected def createElement(
        range: Range,
        isDefinition: Boolean
    ): Location = {
      new Location(
        uri,
        range
      )
    }
  }
}
