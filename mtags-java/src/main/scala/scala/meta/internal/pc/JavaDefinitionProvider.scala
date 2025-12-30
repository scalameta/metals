package scala.meta.internal.pc

import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.control.NonFatal

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class JavaDefinitionProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams
) {

  def definition(): DefinitionResult =
    definitionOffset(params, findTypeDef = false)

  def typeDefinition(): DefinitionResult =
    definitionOffset(params, findTypeDef = true)

  private def isWhitespace: Boolean = {
    params.offset() < 0 ||
    params.offset() >= params.text().length ||
    params.text().charAt(params.offset()).isWhitespace
  }

  private def definitionOffset(
      params: OffsetParams,
      findTypeDef: Boolean
  ): DefinitionResult = {
    params match {
      case range: RangeParams =>
        range.trimWhitespaceInRange
          .flatMap(p => definitionImpl(p, findTypeDef))
          .getOrElse(DefinitionResultImpl.empty)
      case _ if isWhitespace => DefinitionResultImpl.empty
      case _ =>
        definitionImpl(params, findTypeDef).getOrElse(
          DefinitionResultImpl.empty
        )
    }
  }

  private def definitionImpl(
      params: OffsetParams,
      findTypeDef: Boolean
  ): Option[DefinitionResult] = {
    val task: JavacTask =
      compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val position = compiler.positionFromParams(params)
    val node = compiler.compilerTreeNode(scanner, position)

    val result = for {
      n <- node
      trees = Trees.instance(task)
      element = trees.getElement(n)
      if element != null
      // Get the type symbol if we're looking for type definition
      initialTargetElement =
        if (findTypeDef) getTypeElement(element) else element
      if initialTargetElement != null
    } yield {
      val symbol = compiler.semanticdbSymbol(initialTargetElement)

      // Check if the definition is in the current file
      val sourcePositions = trees.getSourcePositions()
      val sourceFile = n.getCompilationUnit().getSourceFile()
      val elementTree = trees.getTree(initialTargetElement)

      val locations: List[Location] =
        if (elementTree != null && sourceFile.toUri() == params.uri()) {

          val initialStartPos = sourcePositions.getStartPosition(
            n.getCompilationUnit(),
            elementTree
          )
          val initialEndPos =
            sourcePositions.getEndPosition(n.getCompilationUnit(), elementTree)
          // For things like default contructors
          val (startPos, endPos, targetElement) =
            if (initialStartPos < 0 || initialEndPos < 0) {
              val enclosing = initialTargetElement.getEnclosingElement()
              val enclosingTree = trees.getTree(enclosing)
              val enclosingStartPos = sourcePositions.getStartPosition(
                n.getCompilationUnit(),
                enclosingTree
              )
              val enclosingEndPos = sourcePositions.getEndPosition(
                n.getCompilationUnit(),
                enclosingTree
              )
              (enclosingStartPos, enclosingEndPos, enclosing)
            } else {
              (initialStartPos, initialEndPos, initialTargetElement)
            }

          if (startPos >= 0 && endPos >= 0) {
            val elementName = targetElement.getSimpleName().toString()
            val (start, end) = compiler.findIndentifierStartAndEnd(
              params.text(),
              elementName,
              startPos.toInt,
              endPos.toInt,
              elementTree,
              n.getCompilationUnit(),
              sourcePositions
            )
            val range = new Range(
              offsetToPosition(start, params.text()),
              offsetToPosition(
                end,
                params.text()
              )
            )
            List(new Location(params.uri().toString(), range))
          } else {
            Nil
          }
        } else {
          // Element is not in current file, use symbol search
          Nil
        }

      val searchLocations = if (locations.isEmpty && symbol.nonEmpty) {
        compiler.search.definition(symbol, params.uri()).asScala.toList
      } else {
        Nil
      }
      DefinitionResultImpl(symbol, (locations ++ searchLocations).asJava)
    }

    (result, node) match {
      case (Some(value), _) => Some(value)
      case (None, Some(node)) =>
        importFallback(node, params)
      case _ => None
    }
  }

  /**
   * Fallback for when element is null but we're at an import statement.
   * This handles static imports where the element resolution fails.
   */
  private def importFallback(
      n: TreePath,
      params: OffsetParams
  ): Option[DefinitionResult] = {
    val parentPath = n.getParentPath()
    if (
      parentPath != null && parentPath.getLeaf().getKind() == Tree.Kind.IMPORT
    ) {
      val leafString = n.getLeaf().toString
      val symbol = convertImportToSemanticdbSymbol(leafString)
      if (symbol.nonEmpty) try {
        val locations =
          compiler.search.definition(symbol, params.uri()).asScala.toList
        Some(DefinitionResultImpl(symbol, locations.asJava))
      } catch {
        case NonFatal(
              _
            ) => // in case a symbol is invalid, we don't want to crash
          None
      }
      else {
        None
      }
    } else {
      None
    }
  }

  /**
   * Converts an import path like "java.lang.Math.max" to a semanticdb symbol
   * like "java/lang/Math.max()." for static method imports.
   */
  private def convertImportToSemanticdbSymbol(importPath: String): String = {
    val parts = importPath.split("\\.")
    if (parts.length >= 2) {
      val methodName = parts.last
      val classPath = parts.init.mkString("/")
      // we use # since there are no objects in Java, and () since we don't know which method is imported
      s"$classPath#$methodName()."
    } else {
      ""
    }
  }

  private def getTypeElement(element: Element): Element = {
    element match {
      case v: VariableElement =>
        v.asType() match {
          case declaredType: javax.lang.model.`type`.DeclaredType =>
            asTypeElement(declaredType, element)
          case _ => element
        }
      case e: ExecutableElement =>
        val returnType = e.getReturnType()
        returnType match {
          case declaredType: javax.lang.model.`type`.DeclaredType =>
            asTypeElement(declaredType, element)
          case _ => element
        }
      case t: TypeElement => t
      case _ => element
    }
  }

  private def asTypeElement(
      declaredType: javax.lang.model.`type`.DeclaredType,
      element: Element
  ): Element = {
    val typeElement = declaredType.asElement()
    if (typeElement != null) typeElement else element
  }

  private def offsetToPosition(offset: Int, text: String): Position = {
    var line = 0
    var character = 0
    var i = 0
    while (i < offset && i < text.length()) {
      if (text.charAt(i) == '\n') {
        line += 1
        character = 0
      } else {
        character += 1
      }
      i += 1
    }
    new Position(line, character)
  }

}
