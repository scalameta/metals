package scala.meta.internal.pc

import java.util.Optional
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier

import scala.annotation.tailrec

import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

class JavaRenameProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams,
    name: Option[String]
) {

  def rename(): List[TextEdit] = {
    findReferences(params)
  }

  def canRenameSymbol(element: Element): Boolean = {
    def isPrivate(element: Element) =
      element.getModifiers.contains(Modifier.PRIVATE)
    @tailrec
    def isLocal(element: Element): Boolean = {
      Option(element.getEnclosingElement()) match {
        case None => false
        case Some(enclosing)
            if isPrivate(element) || enclosing.getKind == ElementKind.METHOD ||
              enclosing.getKind() == ElementKind.CONSTRUCTOR =>
          true
        case Some(other) => isLocal(other)
      }
    }
    isPrivate(element) || isLocal(element)
  }

  def prepareRename(): Optional[Range] = {
    val task: JavacTask =
      compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val trees = Trees.instance(task)
    val position = compiler.positionFromParams(params)
    val node = compiler.compilerTreeNode(scanner, position)
    node match {
      case Some(treePath) =>
        val element = trees.getElement(treePath)
        // TODO also make sure that it's a local symbol
        def atIdentifier = compiler.isAtIdentifier(
          treePath,
          element,
          params.text(),
          params.offset(),
          trees,
          scanner.root
        )
        if (element != null && canRenameSymbol(element) && atIdentifier) {
          val sourcePositions = trees.getSourcePositions()
          val start =
            sourcePositions.getStartPosition(scanner.root, treePath.getLeaf())
          val end =
            sourcePositions.getEndPosition(scanner.root, treePath.getLeaf())
          val (realStart, realEnd) = compiler.findIndentifierStartAndEnd(
            params.text(),
            element.getSimpleName().toString(),
            start.toInt,
            end.toInt,
            treePath.getLeaf(),
            scanner.root,
            sourcePositions
          )
          val range = new Range(
            compiler.offsetToPosition(realStart, params.text()),
            compiler.offsetToPosition(realEnd, params.text())
          )
          Optional.of(range)
        } else {
          Optional.empty()
        }
      case None => Optional.empty()
    }
  }

  private def findReferences(
      offsetParams: OffsetParams
  ): List[TextEdit] = {
    val task: JavacTask =
      compiler.compilationTask(offsetParams.text(), offsetParams.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val trees = Trees.instance(task)
    val position = compiler.positionFromParams(offsetParams)
    val node = compiler.compilerTreeNode(scanner, position)

    node match {
      case Some(treePath) =>
        val element = trees.getElement(treePath)

        def atIdentifier = compiler.isAtIdentifier(
          treePath,
          element,
          params.text(),
          params.offset(),
          trees,
          scanner.root
        )
        if (element != null && canRenameSymbol(element) && atIdentifier) {
          findAllReferences(
            scanner.root,
            element,
            trees,
            offsetParams.text()
          )
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
  ): List[TextEdit] = {
    name match {
      case Some(value) =>
        val scanner =
          new RenamesScanner(
            targetElement,
            trees,
            root,
            text,
            value
          )
        scanner.scan(root, null)
        scanner.result()
      case None => Nil
    }
  }

  private class RenamesScanner(
      targetElement: Element,
      trees: Trees,
      root: CompilationUnitTree,
      text: String,
      newName: String
  ) extends ReferenceScanner[TextEdit](
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
    ): TextEdit = {
      new TextEdit(
        range,
        newName
      )
    }
  }
}
