package scala.meta.internal.jpc

import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.ExecutableType
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters._

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

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
    val result: Option[DefinitionResult] = for {
      (compile, node) <- compiler.nodeAtPosition(params)
      trees = Trees.instance(compile.task)
      element <- processor.transformElement(trees.getElement(node))
    } yield {
      val sourcePositions = trees.getSourcePositions()
      val source = definitionSource(node, trees, element)
      source match {
        case Sourcepath(all @ (firstPath :: _)) =>
          val locations = for {
            p <- all.iterator
            pElement <- processor.transformElement(trees.getElement(p)).toList
            defn <- sourceDefinition(compile, sourcePositions, pElement, p)
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

    result.getOrElse(DefinitionResultImpl.empty)
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

  private def sourceDefinition(
      compile: JavaSourceCompile,
      sourcePositions: SourcePositions,
      element: Element,
      path: TreePath
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
      case None =>
        DefinitionResultImpl.empty
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
