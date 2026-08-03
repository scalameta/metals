package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeMirror

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.OffsetParams

import com.sun.source.tree.BlockTree
import com.sun.source.tree.CaseTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ForLoopTree
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
      case Some((compile, cursorPath)) =>
        params.checkCanceled()
        val context = Context(
          Trees.instance(compile.task),
          compile.cu,
          params.text()
        )

        (for {
          variablePath <- enclosingVariable(cursorPath).toSeq
          variable = variablePath.getLeaf().asInstanceOf[VariableTree]
          typeRange <- typeRange(variable, context).toSeq
          if isSingleDeclaration(variablePath)
          initializer <- Option(variable.getInitializer()).toSeq
          if diagnosticMatchesInitializer(initializer, context)
          if !initializer.toString().contains("<>")
          shortener = JavaTypeShortener.forPath(
            context.cu,
            variablePath
          )
          replacement <- inferInitializerType(
            typeRange,
            context,
            shortener
          ).toSeq
          legacyDimensions = legacyArrayDimensions(variable, typeRange, context)
          if arrayDimensions(replacement) >= legacyDimensions
          typeEdit = new l.TextEdit(
            typeRange.range,
            replacement.stripSuffix("[]" * legacyDimensions)
          )
          importEdit = JavaAutoImportEditor.imports(
            context.text,
            shortener.newImports
          )
        } yield importEdit.toSeq :+ typeEdit).flatten
      case None => Nil
    }
  }

  private def diagnosticMatchesInitializer(
      initializer: Tree,
      context: Context
  ): Boolean =
    diagnosticRange match {
      case Some(range) =>
        context.rangeOf(initializer).exists { initializerRange =>
          initializerRange.range.encloses(range) &&
          initializerRange.range.getEnd() == range.getEnd()
        }
      case None => true
    }

  private def inferInitializerType(
      originalTypeRange: SourceRange,
      originalContext: Context,
      shortener: JavaTypeShortener
  ): Option[String] = {
    val placeholder = "Object"
    val originalTypeLength =
      originalTypeRange.endOffset - originalTypeRange.startOffset
    val adjustedText =
      originalContext.text.patch(
        originalTypeRange.startOffset,
        placeholder,
        originalTypeLength
      )
    val adjustedOffset =
      if (params.offset() >= originalTypeRange.endOffset)
        params.offset() + placeholder.length() - originalTypeLength
      else params.offset()
    val adjustedParams = CompilerOffsetParams(
      params.uri(),
      adjustedText,
      adjustedOffset,
      params.token(),
      params.outlineFiles()
    )

    adjustedParams.checkCanceled()
    compiler.nodeAtPosition(adjustedParams).flatMap {
      case (compile, cursorPath) =>
        val context = Context(
          Trees.instance(compile.task),
          compile.cu,
          adjustedText
        )
        for {
          variablePath <- enclosingVariable(cursorPath)
          variable = variablePath.getLeaf().asInstanceOf[VariableTree]
          initializer <- Option(variable.getInitializer())
          initializerType <- Option(
            context.trees.getTypeMirror(new TreePath(variablePath, initializer))
          )
          replacement <- inferredTypeText(
            initializerType,
            originalContext,
            shortener
          )
        } yield replacement
    }
  }

  private def typeRange(
      variable: VariableTree,
      context: Context
  ): Option[SourceRange] = {
    val name = variable.getName().toString()
    for {
      typeTree <- Option(variable.getType())
      nameStart <- Positions.findNameOffset(
        context.text,
        context.startOf(variable),
        context.endOf(variable),
        name
      )
      range <- context.rangeOf(typeTree)
      adjusted <- trimLegacyArraySuffix(
        range,
        nameStart,
        nameStart + name.length(),
        context
      )
    } yield adjusted
  }

  private def trimLegacyArraySuffix(
      range: SourceRange,
      nameStart: Int,
      nameEnd: Int,
      context: Context
  ): Option[SourceRange] = {
    val legacyArraySuffix =
      range.endOffset > nameEnd &&
        LegacyArrayDimensions.pattern
          .matcher(context.text.substring(nameEnd, range.endOffset))
          .matches()
    if (legacyArraySuffix) {
      val endOffset = context.lastNonWhitespaceBefore(nameStart)
      if (endOffset <= range.startOffset) None
      else {
        val end = endOffset + 1
        Some(
          range.copy(
            range = Positions.toLspRange(
              context.cu.getLineMap(),
              range.startOffset,
              end,
              context.text
            ),
            endOffset = end
          )
        )
      }
    } else Some(range)
  }

  private def inferredTypeText(
      tpe: TypeMirror,
      context: Context,
      shortener: JavaTypeShortener
  ): Option[String] = {
    val shortenedType = shortener.shorten(tpe)
    val renderedType =
      if (shortener.newImports.isEmpty)
        renderType(
          new JavaTypeVisitor().visit(tpe),
          SourceVisibility.from(context.cu)
        )
      else annotation.replaceAllIn(shortenedType, "")
    Option.when(isRenderableType(renderedType))(renderedType)
  }

  private def isSingleDeclaration(variablePath: TreePath): Boolean = {
    val variable = variablePath.getLeaf().asInstanceOf[VariableTree]
    val siblingTrees: Iterable[Tree] =
      variablePath.getParentPath().getLeaf() match {
        case block: BlockTree => block.getStatements().asScala
        case caseTree: CaseTree =>
          Option(caseTree.getStatements()).toSeq.flatMap(_.asScala)
        case classTree: ClassTree => classTree.getMembers().asScala
        case forLoop: ForLoopTree => forLoop.getInitializer().asScala
        case _ => Nil
      }
    !siblingTrees.exists {
      case sibling: VariableTree =>
        (sibling ne variable) && (sibling.getType() eq variable.getType())
      case _ => false
    }
  }
}

object JavaChangeVariableTypeProvider {
  private val qualifiedName: Regex =
    """[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""".r
  private val annotation =
    """@\w+(?:\.\w+)*(?:\([^)]*\))?\s*""".r
  private val LegacyArrayDimensions = """\s*(?:\[\s*\]\s*)+""".r

  private case class Context(
      trees: Trees,
      cu: CompilationUnitTree,
      text: String
  ) {
    def startOf(tree: Tree): Int =
      trees.getSourcePositions().getStartPosition(cu, tree).toInt
    def endOf(tree: Tree): Int =
      trees.getSourcePositions().getEndPosition(cu, tree).toInt

    def rangeOf(tree: Tree): Option[SourceRange] = {
      val start = startOf(tree)
      val end = endOf(tree)
      Option.when(start >= 0 && end >= 0)(
        SourceRange(Positions.toLspRange(trees, cu, tree), start, end)
      )
    }

    def lastNonWhitespaceBefore(offset: Int): Int = {
      var index = offset - 1
      while (index >= 0 && text.charAt(index).isWhitespace) index -= 1
      index
    }
  }

  private case class SourceRange(
      range: l.Range,
      startOffset: Int,
      endOffset: Int
  )

  private def enclosingVariable(path: TreePath): Option[TreePath] =
    if (path == null) None
    else
      path.getLeaf() match {
        case _: VariableTree => Some(path)
        case _ => enclosingVariable(path.getParentPath())
      }

  private def isRenderableType(tpe: String): Boolean =
    tpe.nonEmpty &&
      tpe != "null" &&
      tpe != "<nulltype>" &&
      !tpe.contains("#") &&
      !tpe.contains("anonymous") &&
      !tpe.contains("captured wildcard")

  private def renderType(
      tpe: String,
      sourceVisibility: SourceVisibility
  ): String =
    qualifiedName.replaceAllIn(
      annotation.replaceAllIn(tpe, ""),
      matched =>
        Regex.quoteReplacement(sourceVisibility.visibleName(matched.matched))
    )

  private def legacyArrayDimensions(
      variable: VariableTree,
      typeRange: SourceRange,
      context: Context
  ): Int = {
    val sourceType =
      context.text.substring(typeRange.startOffset, typeRange.endOffset)
    (arrayDimensions(variable.getType().toString()) -
      arrayDimensions(sourceType)).max(0)
  }

  private def arrayDimensions(tpe: String): Int =
    if (tpe.endsWith("[]")) 1 + arrayDimensions(tpe.stripSuffix("[]"))
    else 0

  private case class SourceVisibility(
      currentPackage: Option[String],
      imports: Set[String]
  ) {
    def visibleName(fqn: String): String = {
      val packageName = fqn.substring(0, fqn.lastIndexOf('.'))
      val simpleName = fqn.substring(fqn.lastIndexOf('.') + 1)
      if (isVisible(fqn, packageName)) simpleName else fqn
    }

    private def isVisible(fqn: String, packageName: String): Boolean =
      packageName == "java.lang" ||
        currentPackage.contains(packageName) ||
        imports.contains(fqn) ||
        imports.contains(s"$packageName.*")
  }

  private object SourceVisibility {
    def from(compilationUnit: CompilationUnitTree): SourceVisibility = {
      val currentPackage = Option(compilationUnit.getPackageName()).map(
        _.toString()
      )
      val imports = compilationUnit
        .getImports()
        .asScala
        .map(_.getQualifiedIdentifier().toString())
        .toSet
      SourceVisibility(currentPackage, imports)
    }
  }

}
