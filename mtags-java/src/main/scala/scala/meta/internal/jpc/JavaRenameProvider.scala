package scala.meta.internal.pc

import java.util.Optional
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier

import scala.annotation.tailrec

import scala.meta.internal.jpc.JavaMetalsCompiler
import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

class JavaRenameProvider(
    compiler: JavaMetalsCompiler,
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
    val node = compiler.nodeAtPosition(params, forReferences = true)
    node match {
      case Some((compile, treePath)) =>
        val trees = Trees.instance(compile.task)
        val element = trees.getElement(treePath)

        def atIdentifier = compiler.isAtIdentifier(
          treePath,
          element,
          params.text(),
          params.offset(),
          trees,
          compile.cu
        )
        if (element != null && atIdentifier) {
          val sourcePositions = trees.getSourcePositions()
          val start =
            sourcePositions.getStartPosition(compile.cu, treePath.getLeaf())
          val end =
            sourcePositions.getEndPosition(compile.cu, treePath.getLeaf())
          val (realStart, realEnd) = compiler.findIndentifierStartAndEnd(
            params.text(),
            element.getSimpleName().toString(),
            start.toInt,
            end.toInt,
            treePath.getLeaf(),
            compile.cu,
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
    val node = compiler.nodeAtPosition(params, forReferences = true)

    node match {
      case Some((compile, treePath)) =>
        val trees = Trees.instance(compile.task)
        val element = trees.getElement(treePath)

        def atIdentifier = compiler.isAtIdentifier(
          treePath,
          element,
          params.text(),
          params.offset(),
          trees,
          compile.cu
        )
        if (element != null && canRenameSymbol(element) && atIdentifier) {
          findAllReferences(
            compile.cu,
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
