package scala.meta.internal.jpc

import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.ExecutableType
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

import com.sun.source.tree.Tree
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import com.sun.tools.javac.tree.JCTree.JCFieldAccess
import org.eclipse.{lsp4j => l}

// DefinitionProcessor allows textDocument/typeDefinition to reuse the
// textDocument/definition implementation by only doing a minor transform on
// resolved javac elements.
trait DefinitionProcessor {
  def transformElement(element: Element): Option[Element]
}
object NoopDefinitionProcessor extends DefinitionProcessor {
  def transformElement(element: Element): Option[Element] =
    Option(element)
}
object TypeDefinitionProcessor extends DefinitionProcessor {
  def transformElement(element: Element): Option[Element] =
    // Instead of returning the resolved element, return the element of its type.
    Option(element).flatMap(e => processType(e.asType()))
  private def processType(tpe: TypeMirror): Option[Element] =
    tpe match {
      case d: DeclaredType => Some(d.asElement())
      case exec: ExecutableType => processType(exec.getReturnType())
      case v: TypeVariable => processType(v.getUpperBound())
      case _ => None // Primitive types have no definition
    }
}

class JavaDefinitionProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams,
    processor: DefinitionProcessor = NoopDefinitionProcessor
) {

  def definition(): DefinitionResult = {
    val compileAndNode = compiler.nodeAtPosition(params)
    val result: Option[DefinitionResult] = for {
      (compile, node) <- compileAndNode
      trees = Trees.instance(compile.task)
      element <- processor
        .transformElement(trees.getElement(node))
        .orElse(Option(trees.getElement(node)))
    } yield {
      val sourcePositions = trees.getSourcePositions()
      val source = definitionSource(node, trees, element)
      source match {
        case Sourcepath(all @ (firstPath :: _)) =>
          val locations = for {
            p <- all.iterator
            pElement <- processor.transformElement(trees.getElement(p)).toList
            defn <- sourceDefinition(
              compile,
              sourcePositions,
              pElement,
              p,
              node,
              trees
            )
              .locations()
              .asScala
          } yield defn
          val firstElement = trees.getElement(firstPath)
          DefinitionResultImpl(
            SemanticdbSymbol.fromElement(firstElement),
            locations.toBuffer.asJava
          )
        case Classpath(all @ (firstElement :: _)) =>
          val sym = SemanticdbSymbol.fromElement(firstElement)
          val locations = all.flatMap(e =>
            compiler.search
              .definition(SemanticdbSymbol.fromElement(e), params.uri())
              .asScala
          )
          DefinitionResultImpl(sym, locations.asJava)
        case Classpath(Nil) | Sourcepath(Nil) =>
          DefinitionResultImpl.empty
      }
    }

    result
      .orElse(compileAndNode.flatMap { case (_, node) =>
        importFallback(node, params)
      })
      .getOrElse(DefinitionResultImpl.empty)
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

  private def definitionSource(
      node: TreePath,
      trees: Trees,
      element: Element
  ): DefinitionSource = {
    Option(trees.getPath(element)) match {
      case Some(path) =>
        return Sourcepath(List(path))
      case _ =>
    }

    node.getLeaf() match {
      case _: JCFieldAccess =>
        element.getEnclosingElement() match {
          case parentElement: TypeElement =>
            parentElement match {
              case c: TypeElement =>
                val elementName = element.getSimpleName().toString()
                val ambiguousElements = (for {
                  elem <- c.getEnclosedElements().asScala.iterator
                  if elem.getSimpleName().toString() == elementName
                  processed <- processor.transformElement(elem).toList
                } yield processed).distinct.toList
                val ambiguousPaths = for {
                  member <- ambiguousElements
                  path <- Option(trees.getPath(member)).toList
                } yield path
                if (ambiguousPaths.nonEmpty) {
                  return Sourcepath(ambiguousPaths)
                }
                if (ambiguousElements.nonEmpty) {
                  return Classpath(ambiguousElements)
                }
              case _ =>
            }
          case _ =>
        }
      case _ => None
    }
    Classpath(List(element))
  }

  def localDefinition(
      element: Element,
      elementTree: Tree,
      n: TreePath,
      sourcePositions: SourcePositions,
      trees: Trees
  ): List[l.Location] = {
    val initialStartPos = sourcePositions.getStartPosition(
      n.getCompilationUnit(),
      elementTree
    )
    val initialEndPos =
      sourcePositions.getEndPosition(
        n.getCompilationUnit(),
        elementTree
      )
    // For things like default contructors
    val (startPos, endPos, targetElement) =
      if (initialStartPos < 0 || initialEndPos < 0) {
        val enclosing = element.getEnclosingElement()
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
        (initialStartPos, initialEndPos, element)
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
      val range = new l.Range(
        compiler.offsetToPosition(start, params.text()),
        compiler.offsetToPosition(end, params.text())
      )
      List(new l.Location(params.uri().toString(), range))
    } else {
      Nil
    }
  }

  private def sourceDefinition(
      compile: JavaSourceCompile,
      sourcePositions: SourcePositions,
      element: Element,
      path: TreePath,
      n: TreePath,
      trees: Trees
  ): DefinitionResult = {
    val cu = path.getCompilationUnit()
    val start = sourcePositions.getStartPosition(cu, path.getLeaf())
    var end = sourcePositions.getEndPosition(cu, path.getLeaf())
    if (end < start) {
      end = start
    }
    // Reuse the SemanticdbVisitor to extract the exact range of the identifier
    // that defines this element. The javac Tree only gives us access to the
    // start/end offsets of the full statement, and the algorithm to extract the
    // identifier range requires several heuristics.
    val semanticdbProvider = new JavaSemanticdbProvider(compiler)
    val semanticdb = semanticdbProvider.textDocumentFromSource(
      compile,
      Some(TargetRange(cu, start, end))
    )
    val semanticdbSymbol = SemanticdbSymbol.fromElement(element)
    val definitionOccurrence = semanticdb
      .getOccurrencesList()
      .asScala
      .find(o =>
        o.getSymbol().equals(semanticdbSymbol) && o
          .getRole() == Semanticdb.SymbolOccurrence.Role.DEFINITION
      )
    definitionOccurrence match {
      case None => {
        val elementTree = trees.getTree(element)
        val sourceFile = n.getCompilationUnit().getSourceFile()
        val locations =
          if (elementTree != null && sourceFile.toUri() == params.uri()) {
            localDefinition(element, elementTree, n, sourcePositions, trees)
          } else {
            // Element is not in current file
            Nil
          }
        DefinitionResultImpl(semanticdbSymbol, locations.asJava)
      }
      case Some(occ) =>
        val uri = compiler.guessOriginalUri(cu.getSourceFile())
        DefinitionResultImpl(
          semanticdbSymbol,
          List(
            new l.Location(uri.toString(), occ.getRange().toLspRange)
          ).asJava
        )
    }
  }
}

private sealed abstract class DefinitionSource
private final case class Sourcepath(paths: List[TreePath])
    extends DefinitionSource
private final case class Classpath(elements: List[Element])
    extends DefinitionSource
