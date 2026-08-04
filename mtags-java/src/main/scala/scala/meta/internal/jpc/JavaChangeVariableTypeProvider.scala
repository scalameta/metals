package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeKind
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.OffsetParams

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CaseTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.ParameterizedTypeTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

final class JavaChangeVariableTypeProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams,
    diagnosticRange: Option[l.Range]
) {

  import JavaChangeVariableTypeProvider._

  def textEdits(): Seq[l.TextEdit] = {
    params.checkCanceled()
    compiler.nodeAtPosition(params) match {
      case Some((compilation, cursorPath)) =>
        params.checkCanceled()
        val source = new SourceContext(compilation, params.text())
        findTargetVariable(cursorPath, source)
          .map(createEdits(_, source))
          .getOrElse(Nil)
      case None => Nil
    }
  }

  private def findTargetVariable(
      cursorPath: TreePath,
      source: SourceContext
  ): Option[TargetVariable] =
    for {
      variablePath <- enclosingVariablePath(cursorPath)
      variable = variablePath.getLeaf().asInstanceOf[VariableTree]
      typeRange <- declaredTypeRange(variable, source)
      if hasSingleDeclarator(variablePath, source)
      initializer <- Option(variable.getInitializer())
      if diagnosticMatchesInitializer(initializer, source)
      if !usesDiamondOperator(initializer)
    } yield TargetVariable(variablePath, variable, typeRange)

  private def createEdits(
      target: TargetVariable,
      source: SourceContext
  ): Seq[l.TextEdit] = {
    val shortener = source.typeShortener(target.path)
    val edits = for {
      replacementType <- inferReplacementType(target, source, shortener)
      legacyArrayDimensions = legacyArrayDimensionCount(
        target.variable,
        target.declaredTypeRange,
        source
      )
      if arrayDimensionCount(replacementType) >= legacyArrayDimensions
    } yield {
      val typeEdit = new l.TextEdit(
        target.declaredTypeRange.range,
        replacementType.stripSuffix("[]" * legacyArrayDimensions)
      )
      JavaAutoImportEditor
        .imports(source.text, shortener.newImports)
        .toSeq :+ typeEdit
    }
    edits.getOrElse(Nil)
  }

  private def diagnosticMatchesInitializer(
      initializer: Tree,
      source: SourceContext
  ): Boolean =
    diagnosticRange.forall { range =>
      source.rangeOf(initializer).exists { initializerRange =>
        initializerRange.range.encloses(range) &&
        (initializer.getKind() == Tree.Kind.SWITCH_EXPRESSION ||
          initializerRange.range.getEnd() == range.getEnd())
      }
    }

  private def inferReplacementType(
      target: TargetVariable,
      source: SourceContext,
      shortener: JavaTypeShortener
  ): Option[String] = {
    // Javac does not expose a usable initializer type while the assignment is
    // ill-typed. Replace the declared type and analyze the source again.
    val typeRange = target.declaredTypeRange
    val placeholder = target.variable.getInitializer().getKind() match {
      case Tree.Kind.CONDITIONAL_EXPRESSION | Tree.Kind.SWITCH_EXPRESSION =>
        "var"
      case _ => "Object"
    }
    val typeLength = typeRange.endOffset - typeRange.startOffset
    val patchedText = source.text.patch(
      typeRange.startOffset,
      placeholder,
      typeLength
    )
    val patchedOffset =
      if (params.offset() >= typeRange.endOffset)
        params.offset() + placeholder.length() - typeLength
      else params.offset()
    val patchedParams = CompilerOffsetParams(
      params.uri(),
      patchedText,
      patchedOffset,
      params.token(),
      params.outlineFiles()
    )

    patchedParams.checkCanceled()
    compiler.nodeAtPosition(patchedParams).flatMap {
      case (compilation, cursorPath) =>
        val patchedSource = new SourceContext(compilation, patchedText)
        for {
          variablePath <- enclosingVariablePath(cursorPath)
          variable = variablePath.getLeaf().asInstanceOf[VariableTree]
          initializer <- Option(variable.getInitializer())
          replacementType <- inferredInitializerTypes(
            variablePath,
            initializer,
            patchedSource
          ).flatMap(renderInferredType(_, shortener)).headOption
        } yield replacementType
    }
  }

  private def inferredInitializerTypes(
      variablePath: TreePath,
      initializer: Tree,
      source: SourceContext
  ): Seq[TypeMirror] = {
    val initializerPath = new TreePath(variablePath, initializer)
    val inferredTypePath = initializer match {
      case newClass: NewClassTree if newClass.getClassBody() != null =>
        new TreePath(initializerPath, newClass.getIdentifier())
      case _ => initializerPath
    }
    val declaredReturnType = initializer match {
      case _: MethodInvocationTree =>
        source.declaredReturnType(initializerPath).toSeq
      case _ => Nil
    }
    source.typeOf(inferredTypePath).toSeq ++ declaredReturnType
  }

  private def declaredTypeRange(
      variable: VariableTree,
      source: SourceContext
  ): Option[SourceRange] = {
    val variableName = variable.getName().toString()
    for {
      typeTree <- Option(variable.getType())
      nameStart <- Positions.findNameOffset(
        source.text,
        source.startOf(variable),
        source.endOf(variable),
        variableName
      )
      sourceRange <- source.rangeOf(typeTree)
      (range, endOffset) <- Positions.trimLegacyArraySuffix(
        sourceRange.startOffset,
        sourceRange.endOffset,
        nameStart,
        nameStart + variableName.length(),
        source.lineMap,
        source.text
      )
    } yield sourceRange.copy(range = range, endOffset = endOffset)
  }

  private def renderInferredType(
      initializerType: TypeMirror,
      shortener: JavaTypeShortener
  ): Option[String] =
    initializerType.getKind() match {
      case TypeKind.ERROR | TypeKind.INTERSECTION | TypeKind.NONE |
          TypeKind.VOID =>
        None
      case _ =>
        val renderedType =
          TypeAnnotation.replaceAllIn(shortener.shorten(initializerType), "")
        Option.when(isRenderableType(renderedType))(renderedType)
    }

  private def hasSingleDeclarator(
      variablePath: TreePath,
      source: SourceContext
  ): Boolean = {
    val variable = variablePath.getLeaf().asInstanceOf[VariableTree]
    val typeStart =
      Option(variable.getType()).map(source.startOf).filter(_ >= 0)
    // Javac creates one VariableTree per declarator. Trees originating from
    // the same declaration share the start of their type source range.
    !siblingVariables(variablePath).exists { sibling =>
      (sibling ne variable) && typeStart.exists { start =>
        Option(sibling.getType()).exists(source.startOf(_) == start)
      }
    }
  }

  private def siblingVariables(
      variablePath: TreePath
  ): Iterable[VariableTree] = {
    val siblings: Iterable[Tree] =
      variablePath.getParentPath().getLeaf() match {
        case block: BlockTree => block.getStatements().asScala
        case caseTree: CaseTree =>
          Option(caseTree.getStatements()).toSeq.flatMap(_.asScala)
        case classTree: ClassTree => classTree.getMembers().asScala
        case forLoop: ForLoopTree => forLoop.getInitializer().asScala
        case _ => Nil
      }
    siblings.collect { case variable: VariableTree => variable }
  }

  private def usesDiamondOperator(initializer: Tree): Boolean =
    initializer match {
      case newClass: NewClassTree =>
        newClass.getIdentifier() match {
          case paramType: ParameterizedTypeTree =>
            paramType.getTypeArguments().isEmpty
          case _ => false
        }
      case _ => false
    }
}

object JavaChangeVariableTypeProvider {
  private val TypeAnnotation =
    """@\w+(?:\.\w+)*(?:\((?:[^()]*|\([^()]*\))*\))?\s*""".r

  private class SourceContext(
      compilation: JavaSourceCompile,
      val text: String
  ) {
    private val task = compilation.task
    private val compilationUnit = compilation.cu
    private val trees = Trees.instance(task)
    private val sourcePositions = trees.getSourcePositions()
    val lineMap: LineMap = compilationUnit.getLineMap()

    def typeShortener(path: TreePath): JavaTypeShortener =
      JavaTypeShortener.forPath(compilationUnit, path, task.getElements())

    def startOf(tree: Tree): Int =
      sourcePositions.getStartPosition(compilationUnit, tree).toInt
    def endOf(tree: Tree): Int =
      sourcePositions.getEndPosition(compilationUnit, tree).toInt

    def typeOf(path: TreePath): Option[TypeMirror] =
      Option(trees.getTypeMirror(path))

    def declaredReturnType(path: TreePath): Option[TypeMirror] =
      Option(trees.getElement(path)).collect {
        case method: ExecutableElement
            if method.getTypeParameters().isEmpty &&
              hasNoEnclosingTypeParameters(method.getEnclosingElement()) =>
          method.getReturnType()
      }

    def rangeOf(tree: Tree): Option[SourceRange] = {
      val start = startOf(tree)
      val end = endOf(tree)
      Option.when(start >= 0 && end >= 0)(
        SourceRange(
          Positions.toLspRange(lineMap, start, end, text),
          start,
          end
        )
      )
    }
  }

  private case class TargetVariable(
      path: TreePath,
      variable: VariableTree,
      declaredTypeRange: SourceRange
  )

  private case class SourceRange(
      range: l.Range,
      startOffset: Int,
      endOffset: Int
  )

  private def enclosingVariablePath(path: TreePath): Option[TreePath] =
    if (path == null) None
    else
      path.getLeaf() match {
        case _: VariableTree => Some(path)
        case _ => enclosingVariablePath(path.getParentPath())
      }

  private def isRenderableType(tpe: String): Boolean =
    tpe.nonEmpty &&
      tpe != "null" &&
      tpe != "<nulltype>" &&
      !tpe.contains("#") &&
      !tpe.contains("anonymous") &&
      !tpe.contains("captured wildcard")

  private def hasNoEnclosingTypeParameters(element: Element): Boolean =
    element match {
      case tpe: TypeElement =>
        tpe.getTypeParameters().isEmpty &&
        hasNoEnclosingTypeParameters(tpe.getEnclosingElement())
      case _ => true
    }

  private def legacyArrayDimensionCount(
      variable: VariableTree,
      typeRange: SourceRange,
      source: SourceContext
  ): Int = {
    val sourceType =
      source.text.substring(typeRange.startOffset, typeRange.endOffset)
    (arrayDimensionCount(variable.getType().toString()) -
      arrayDimensionCount(sourceType)).max(0)
  }

  private def arrayDimensionCount(tpe: String): Int =
    if (tpe.endsWith("[]"))
      1 + arrayDimensionCount(tpe.stripSuffix("[]"))
    else 0
}
