package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeMirror

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
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

        for {
          variablePath <- enclosingVariable(cursorPath).toSeq
          variable = variablePath.getLeaf().asInstanceOf[VariableTree]
          located = LocatedVariable(variable, variablePath)
          typeRange <- typeRange(variable, context).toSeq
          if isSingleDeclaration(located, typeRange, context)
          initializer <- Option(variable.getInitializer()).toSeq
          if diagnosticMatchesInitializer(initializer, context)
          if !initializer.toString().contains("<>")
          replacement <- inferInitializerType(typeRange, context).toSeq
          legacyDimensions = legacyArrayDimensions(located, typeRange, context)
          if arrayDimensions(replacement) >= legacyDimensions
        } yield new l.TextEdit(
          typeRange.range,
          stripArrayDimensions(replacement, legacyDimensions)
        )
      case None => Nil
    }
  }

  private def diagnosticMatchesInitializer(
      initializer: Tree,
      context: Context
  ): Boolean =
    diagnosticRange match {
      case Some(range) =>
        treeRange(initializer, context).exists { initializerRange =>
          encloses(initializerRange.range, range) &&
          samePosition(initializerRange.range.getEnd(), range.getEnd())
        }
      case None => true
    }

  private def inferInitializerType(
      originalTypeRange: JavaTypeRange,
      originalContext: Context
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
          initializerType <- typeOf(variablePath, initializer, context)
          replacement <- inferredTypeText(initializerType, originalContext)
        } yield replacement
    }
  }

  private def typeRange(
      variable: VariableTree,
      context: Context
  ): Option[JavaTypeRange] =
    for {
      typeTree <- Option(variable.getType())
      nameRange <- nameRange(variable, context)
      range <- treeRange(typeTree, context)
      adjusted <- trimLegacyArraySuffix(range, nameRange, context)
    } yield adjusted

  private def trimLegacyArraySuffix(
      range: JavaTypeRange,
      nameRange: JavaTypeRange,
      context: Context
  ): Option[JavaTypeRange] = {
    val legacyArraySuffix =
      range.endOffset > nameRange.endOffset &&
        onlyLegacyArrayDimensions(
          context.text.substring(nameRange.endOffset, range.endOffset)
        )
    if (legacyArraySuffix) {
      val endOffset = lastNonWhitespaceBefore(nameRange.startOffset, context)
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

  private def typeOf(
      variablePath: TreePath,
      initializer: Tree,
      context: Context
  ): Option[TypeMirror] =
    Option(context.trees.getTypeMirror(new TreePath(variablePath, initializer)))

  private def inferredTypeText(
      tpe: TypeMirror,
      context: Context
  ): Option[String] = {
    val fullType = new JavaTypeVisitor().visit(tpe)
    Option(renderType(fullType, SourceVisibility.from(context.cu)))
      .filter(isRenderableType)
  }

  private def treeRange(
      tree: Tree,
      context: Context
  ): Option[JavaTypeRange] = {
    val start = context.startOf(tree)
    val end = context.endOf(tree)
    if (start < 0 || end < 0) None
    else
      Some(
        JavaTypeRange(
          Positions.toLspRange(
            context.cu.getLineMap(),
            start,
            end,
            context.text
          ),
          start,
          end
        )
      )
  }

  private def nameRange(
      variable: VariableTree,
      context: Context
  ): Option[JavaTypeRange] = {
    val name = variable.getName().toString()
    findNameOffset(
      context.text,
      context.startOf(variable),
      context.endOf(variable),
      name
    ).map { start =>
      val end = start + name.length()
      JavaTypeRange(
        Positions.toLspRange(context.cu.getLineMap(), start, end, context.text),
        start,
        end
      )
    }
  }

  private def isSingleDeclaration(
      variable: LocatedVariable,
      typeRange: JavaTypeRange,
      context: Context
  ): Boolean = {
    val text = context.text
    val nameStart = nameRange(variable.tree, context)
      .map(_.startOffset)
      .getOrElse(Int.MaxValue)
    val typeAdjacentToName =
      typeRange.endOffset <= nameStart &&
        text.substring(typeRange.endOffset, nameStart).forall(_.isWhitespace)
    val nextOffset =
      text.indexWhere(!_.isWhitespace, context.endOf(variable.tree).max(0))
    val continuesWithComma = nextOffset >= 0 && text.charAt(nextOffset) == ','
    typeAdjacentToName && !continuesWithComma
  }
}

object JavaChangeVariableTypeProvider {
  private val qualifiedName: Regex =
    """[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""".r
  private val annotation: Regex =
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
  }

  private case class LocatedVariable(
      tree: VariableTree,
      path: TreePath
  )

  private case class JavaTypeRange(
      range: l.Range,
      startOffset: Int,
      endOffset: Int
  )

  private def encloses(outer: l.Range, inner: l.Range): Boolean =
    comparePosition(outer.getStart(), inner.getStart()) <= 0 &&
      comparePosition(inner.getEnd(), outer.getEnd()) <= 0

  private def samePosition(left: l.Position, right: l.Position): Boolean =
    comparePosition(left, right) == 0

  private def comparePosition(left: l.Position, right: l.Position): Int = {
    val line = left.getLine().compare(right.getLine())
    if (line == 0) left.getCharacter().compare(right.getCharacter())
    else line
  }

  private def enclosingVariable(path: TreePath): Option[TreePath] =
    if (path == null) None
    else
      path.getLeaf() match {
        case _: VariableTree => Some(path)
        case _ => enclosingVariable(path.getParentPath())
      }

  private def findNameOffset(
      text: String,
      startPos: Int,
      endPos: Int,
      name: String
  ): Option[Int] = {
    if (startPos < 0 || endPos < 0) None
    else {
      val searchEnd = Math.min(endPos, text.length())
      (startPos until searchEnd)
        .find { offset =>
          val endOffset = offset + name.length()
          Character.isJavaIdentifierStart(text.charAt(offset)) &&
          text.startsWith(name, offset) &&
          (offset == 0 ||
            !Character.isJavaIdentifierPart(text.charAt(offset - 1))) &&
          (endOffset >= text.length() ||
            !Character.isJavaIdentifierPart(text.charAt(endOffset)))
        }
    }
  }

  private def onlyLegacyArrayDimensions(suffix: String): Boolean =
    suffix match {
      case LegacyArrayDimensions() => true
      case _ => false
    }

  private def lastNonWhitespaceBefore(
      offset: Int,
      context: Context
  ): Int = {
    var i = offset - 1
    while (i >= 0 && context.text.charAt(i).isWhitespace) i -= 1
    i
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
  ): String = {
    qualifiedName.replaceAllIn(
      typeName(tpe),
      m => {
        val fqn = m.matched
        Regex.quoteReplacement(sourceVisibility.visibleName(fqn))
      }
    )
  }

  private def typeName(tpe: String): String =
    annotation.replaceAllIn(tpe, "")

  private def stripArrayDimensions(
      tpe: String,
      dimensions: Int
  ): String = {
    var result = tpe
    var remaining = dimensions
    while (remaining > 0 && result.endsWith("[]")) {
      result = result.stripSuffix("[]")
      remaining -= 1
    }
    result
  }

  private def legacyArrayDimensions(
      variable: LocatedVariable,
      typeRange: JavaTypeRange,
      context: Context
  ): Int = {
    val sourceType =
      context.text.substring(typeRange.startOffset, typeRange.endOffset)
    (arrayDimensions(variable.tree.getType().toString()) -
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
      val imports =
        compilationUnit
          .getImports()
          .asScala
          .map { importTree =>
            importTree.getQualifiedIdentifier().toString()
          }
          .toSet
      SourceVisibility(currentPackage, imports)
    }
  }
}
