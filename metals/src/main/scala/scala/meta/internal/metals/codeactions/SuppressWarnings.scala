package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaAnnotation
import scala.meta.internal.parsing.JavaMember
import scala.meta.internal.parsing.JavaRange
import scala.meta.internal.parsing.JavaTrees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class SuppressWarnings(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import SuppressWarnings._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val actions = for {
      text <- buffers.get(path).orElse(path.readTextOpt).toSeq
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      warningName <- warningName(diagnostic).toSeq
      if range.overlapsWith(diagnostic.getRange()) ||
        isZeroRange(diagnostic.getRange())
      position =
        if (range.overlapsWith(diagnostic.getRange()))
          diagnostic.getRange().getStart()
        else range.getStart()
      member <- enclosingMember(text, path, position).toSeq
      edit <- suppressEdit(text, path, member, warningName).toSeq
    } yield CodeActionBuilder.build(
      title(warningName),
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
    actions.distinctBy(_.getTitle())
  }

  private def enclosingMember(
      text: String,
      path: AbsolutePath,
      position: l.Position,
  ): Option[SuppressTarget] = {
    val positionOffset = text.lspPositionToIndex(position)
    javaTrees
      .findEnclosingJavaVariable(path, position, onNameOnly = false)
      .filter(variable =>
        variable.isStandaloneDeclaration &&
          positionOffset <= variable.nameRange.endOffset
      )
      .map(variable => SuppressTarget(variable, variable.nameRange))
      .orElse(
        javaTrees
          .findEnclosingJavaMethod(path, position)
          .map(method => SuppressTarget(method, method.nameRange))
      )
      .orElse(
        javaTrees
          .findEnclosingJavaClass(path, position)
          .map(cls => SuppressTarget(cls, cls.nameRange))
      )
  }

  private def suppressEdit(
      text: String,
      path: scala.meta.io.AbsolutePath,
      target: SuppressTarget,
      warningName: String,
  ): Option[l.TextEdit] = {
    val annotations = javaTrees.memberAnnotations(path, target.member)
    existingSuppressWarnings(annotations) match {
      case Some(existing) => appendWarningEdit(text, existing, warningName)
      case None =>
        Some(insertSuppressWarningsEdit(text, target, warningName, annotations))
    }
  }

  private def insertSuppressWarningsEdit(
      text: String,
      target: SuppressTarget,
      warningName: String,
      annotations: List[JavaAnnotation],
  ): l.TextEdit = {
    val declarationOffset = declarationStartOffset(text, target, annotations)
    val declarationStart = text.indexToLspPosition(declarationOffset)
    val linePrefix =
      JavaMemberInsertion.linePrefix(text, declarationOffset)
    val (position, newText) =
      if (linePrefix.forall(_.isWhitespace))
        (
          new l.Position(declarationStart.getLine(), 0),
          s"""$linePrefix@SuppressWarnings("$warningName")
             |""".stripMargin,
        )
      else (declarationStart, s"""@SuppressWarnings("$warningName") """)

    new l.TextEdit(new l.Range(position, position), newText)
  }

  private def declarationStartOffset(
      text: String,
      target: SuppressTarget,
      annotations: List[JavaAnnotation],
  ): Int = {
    val afterAnnotations = annotations
      .maxByOption(_.range.endOffset)
      .map(_.range.endOffset)
      .getOrElse(target.member.range.startOffset)
    var offset = afterAnnotations
    while (
      offset < target.nameRange.startOffset && text.charAt(offset).isWhitespace
    )
      offset += 1
    offset
  }
}

object SuppressWarnings {
  def title(warningName: String): String =
    s"""Add @SuppressWarnings("$warningName")"""

  private val DiagnosticSubstrings: List[(String, String)] = List(
    "missing.deprecated.annotation" -> "dep-ann",
    "deprecated.for.removal" -> "removal",
    "requires-transitive-automatic" -> "requires-transitive-automatic",
    "requires-automatic" -> "requires-automatic",
    "output-file-clash" -> "output-file-clash",
    "missing-explicit-ctor" -> "missing-explicit-ctor",
    "loss.of.precision" -> "lossy-conversions",
    "fall-through" -> "fallthrough",
    "ambiguous.overload" -> "overloads",
    "override.equals" -> "overrides",
    "trailing.white.space" -> "text-blocks",
    "this.escape" -> "this-escape",
    "synchronize" -> "synchronization",
    "auxiliaryclass" -> "auxiliaryclass",
    "raw.class" -> "rawtypes",
    "div.zero" -> "divzero",
    "serialversionuid" -> "serial",
    "svuid" -> "serial",
    "classfile" -> "classfile",
    "varargs" -> "varargs",
    "unchecked" -> "unchecked",
    "deprecated" -> "deprecation",
    "cast" -> "cast",
    "divzero" -> "divzero",
    "empty" -> "empty",
    "exports" -> "exports",
    "fallthrough" -> "fallthrough",
    "finally" -> "finally",
    "lossy-conversions" -> "lossy-conversions",
    "module" -> "module",
    "opens" -> "opens",
    "options" -> "options",
    "overloads" -> "overloads",
    "overrides" -> "overrides",
    "path" -> "path",
    "preview" -> "preview",
    "processing" -> "processing",
    "rawtypes" -> "rawtypes",
    "removal" -> "removal",
    "serial" -> "serial",
    "static" -> "static",
    "strictfp" -> "strictfp",
    "synchronization" -> "synchronization",
    "text-blocks" -> "text-blocks",
    "this-escape" -> "this-escape",
    "try" -> "try",
  )

  private def warningName(diagnostic: l.Diagnostic): Option[String] =
    if (diagnostic.getSource() == "javac") {
      val code = Option(diagnostic.getCode())
        .collect { case code if code.isLeft() => code.getLeft() }
        .getOrElse("")
        .toLowerCase()
      val isWarning = code.startsWith("compiler.warn.") ||
        diagnostic.getSeverity() == l.DiagnosticSeverity.Warning
      if (isWarning) {
        val message =
          Option(diagnostic.getMessage())
            .map(_.toString())
            .getOrElse("")
            .toLowerCase()
        warningNameFrom(code).orElse(warningNameFrom(message))
      } else None
    } else None

  private def warningNameFrom(text: String): Option[String] =
    DiagnosticSubstrings.collectFirst {
      case (substr, name) if text.contains(substr) => name
    }

  private def isZeroRange(range: l.Range): Boolean =
    range.getStart().getLine() == 0 &&
      range.getStart().getCharacter() == 0 &&
      range.getEnd().getLine() == 0 &&
      range.getEnd().getCharacter() == 0

  private def existingSuppressWarnings(
      annotations: List[JavaAnnotation]
  ): Option[ExistingSuppressWarnings] =
    annotations
      .collectFirst {
        case ann
            if ann.name == "SuppressWarnings" ||
              ann.name.endsWith(".SuppressWarnings") =>
          ann.argsRange
      }
      .flatten
      .map { case (open, close) => ExistingSuppressWarnings(open, close) }

  private def appendWarningEdit(
      text: String,
      existing: ExistingSuppressWarnings,
      warningName: String,
  ): Option[l.TextEdit] = {
    val insideStart = existing.openParenOffset + 1
    val insideEnd = existing.closeParenOffset
    val inside = text.substring(insideStart, insideEnd)
    if (inside.contains(s""""$warningName"""")) None
    else {
      val trimmed = inside.trim()
      val (range, newText) =
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
          val closeBrace = insideEnd - inside.reverse.indexOf('}') - 1
          (
            new l.Range(
              text.indexToLspPosition(closeBrace),
              text.indexToLspPosition(closeBrace),
            ),
            s""", "$warningName"""",
          )
        } else {
          (
            new l.Range(
              text.indexToLspPosition(insideStart),
              text.indexToLspPosition(insideEnd),
            ),
            s"""{$trimmed, "$warningName"}""",
          )
        }
      Some(new l.TextEdit(range, newText))
    }
  }

  private case class SuppressTarget(
      member: JavaMember,
      nameRange: JavaRange,
  )

  private case class ExistingSuppressWarnings(
      openParenOffset: Int,
      closeParenOffset: Int,
  )
}
