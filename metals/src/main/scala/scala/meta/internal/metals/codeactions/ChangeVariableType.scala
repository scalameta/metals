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
      variable <- javaTrees.findEnclosingJavaVariable(path, position).toSeq
      typeRange <- variable.typeRange.toSeq
      initializerRange <- variable.initializerRange.toSeq
      if isSingleDeclaration(variable, typeRange, text)
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      foundType <- changedType(diagnostic, variable, initializerRange).toSeq
    } yield build(path, diagnostic, typeRange, foundType, text)
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
      foundType: String,
      text: String,
  ): l.CodeAction = {
    val edit = new l.TextEdit(typeRange.range, renderType(foundType, text))
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
  private val packageDeclaration: Regex =
    """(?m)^\s*package\s+([\w.]+)\s*;""".r
  private val importDeclaration: Regex =
    """(?m)^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;""".r
  private val annotation: Regex =
    """@\w+(?:\.\w+)*(?:\([^)]*\))?\s*""".r

  private def isRenderableType(tpe: String): Boolean =
    !tpe.contains("#") && !tpe.contains("anonymous")

  private def simpleForm(tpe: String): String =
    qualifiedName
      .replaceAllIn(
        tpe,
        m => m.matched.substring(m.matched.lastIndexOf('.') + 1),
      )
      .replaceAll("""\s+""", "")

  private def typeName(tpe: String): String =
    annotation.replaceAllIn(tpe, "")

  private def renderType(tpe: String, text: String): String = {
    val source = SourceVisibility.from(text)
    qualifiedName.replaceAllIn(
      tpe,
      m => {
        val fqn = m.matched
        Regex.quoteReplacement(source.visibleName(fqn))
      },
    )
  }

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
    def from(text: String): SourceVisibility = {
      val currentPackage =
        packageDeclaration.findFirstMatchIn(text).map(_.group(1))
      val imports =
        importDeclaration.findAllMatchIn(text).map(_.group(1)).toSet
      SourceVisibility(currentPackage, imports)
    }
  }
}
