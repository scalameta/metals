package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.matching.Regex

import scala.meta.internal.jpc.JavacDiagnostic
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaRange
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.JavaVariable
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import com.sun.source.tree.CompilationUnitTree
import org.eclipse.{lsp4j => l}

class ChangeVariableType(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import ChangeVariableType._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val position = params.getRange().getStart()

    for {
      text <- buffers.get(path).orElse(path.readTextOpt).toSeq
      sourceVisibility <- javaTrees.get(path).map(SourceVisibility.from).toSeq
      variable <- javaTrees.findEnclosingJavaVariable(path, position).toSeq
      typeRange <- variable.typeRange.toSeq
      initializerRange <- variable.initializerRange.toSeq
      if isSingleDeclaration(variable, typeRange, text)
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      foundType <- changedType(diagnostic, variable, initializerRange).toSeq
      legacyDimensions = legacyArrayDimensions(variable, typeRange, text)
      if arrayDimensions(foundType) >= legacyDimensions
      replacement = renderType(
        foundType,
        sourceVisibility,
        legacyDimensions,
      )
    } yield build(path, diagnostic, typeRange, replacement)
  }

  private def changedType(
      diagnostic: l.Diagnostic,
      variable: JavaVariable,
      initializerRange: JavaRange,
  ): Option[String] =
    for {
      mismatch <- JavacDiagnostic.IncompatibleTypes.unapply(diagnostic)
      if isRenderableType(mismatch.found)
      if isInitializerMismatch(diagnostic, initializerRange)
      if sameType(mismatch.required, variable.typ)
    } yield mismatch.found

  private def isInitializerMismatch(
      diagnostic: l.Diagnostic,
      initializerRange: JavaRange,
  ): Boolean =
    initializerRange.range.encloses(diagnostic.getRange()) &&
      initializerRange.range.getEnd() == diagnostic.getRange().getEnd()

  private def isSingleDeclaration(
      variable: JavaVariable,
      typeRange: JavaRange,
      text: String,
  ): Boolean = {
    val typeAdjacentToName =
      typeRange.endOffset <= variable.nameRange.startOffset &&
        text
          .substring(typeRange.endOffset, variable.nameRange.startOffset)
          .forall(_.isWhitespace)
    val nextOffset =
      text.indexWhere(!_.isWhitespace, variable.range.endOffset)
    val continuesWithComma = nextOffset >= 0 && text.charAt(nextOffset) == ','
    typeAdjacentToName && !continuesWithComma
  }

  private def build(
      path: AbsolutePath,
      diagnostic: l.Diagnostic,
      typeRange: JavaRange,
      replacement: String,
  ): l.CodeAction = {
    val edit = new l.TextEdit(typeRange.range, replacement)
    CodeActionBuilder.build(
      title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
  }
}

object ChangeVariableType {
  val title = "Change variable type to match assigned value"

  private val qualifiedName: Regex =
    """[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""".r
  private val annotation: Regex =
    """@\w+(?:\.\w+)*(?:\([^)]*\))?\s*""".r

  private def isRenderableType(tpe: String): Boolean =
    !tpe.contains("#") && !tpe.contains("anonymous")

  private def simpleForm(tpe: String): String =
    qualifiedName
      .replaceAllIn(
        tpe,
        m =>
          Regex.quoteReplacement(
            m.matched.substring(m.matched.lastIndexOf('.') + 1)
          ),
      )
      .replaceAll("""\s+""", "")

  private def typeName(tpe: String): String =
    annotation.replaceAllIn(tpe, "")

  private def renderType(
      tpe: String,
      sourceVisibility: SourceVisibility,
      legacyArrayDimensions: Int,
  ): String = {
    qualifiedName.replaceAllIn(
      stripArrayDimensions(tpe, legacyArrayDimensions),
      m => {
        val fqn = m.matched
        Regex.quoteReplacement(sourceVisibility.visibleName(fqn))
      },
    )
  }

  private def stripArrayDimensions(
      tpe: String,
      dimensions: Int,
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
      variable: JavaVariable,
      typeRange: JavaRange,
      text: String,
  ): Int = {
    val sourceType = text.substring(typeRange.startOffset, typeRange.endOffset)
    (arrayDimensions(variable.typ) - arrayDimensions(sourceType)).max(0)
  }

  private def arrayDimensions(tpe: String): Int =
    if (tpe.endsWith("[]")) 1 + arrayDimensions(tpe.stripSuffix("[]"))
    else 0

  private def sameType(left: String, right: String): Boolean =
    simpleForm(left) == simpleForm(typeName(right))

  private case class SourceVisibility(
      currentPackage: Option[String],
      imports: Set[String],
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
