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

import com.google.gson.JsonPrimitive
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
      member <- enclosingMember(path, position).toSeq
      edit <- suppressEdit(text, path, member, warningName).toSeq
    } yield CodeActionBuilder.build(
      title(warningName),
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
    actions.distinctBy(_.getEdit())
  }

  private def enclosingMember(
      path: AbsolutePath,
      position: l.Position,
  ): Option[SuppressTarget] =
    javaTrees
      .findEnclosingJavaVariable(path, position, onNameOnly = false)
      .filter(variable =>
        variable.isStandaloneDeclaration &&
          position <= variable.nameRange.getEnd()
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

  private def warningName(diagnostic: l.Diagnostic): Option[String] =
    Option
      .when(diagnostic.getSource() == "javac")(diagnostic.getData())
      .flatMap {
        case value: String => Some(value)
        case value: JsonPrimitive if value.isString() =>
          Some(value.getAsString())
        case _ => None
      }

  private def isZeroRange(range: l.Range): Boolean =
    range.isOffset &&
      range.getStart().getLine() == 0 &&
      range.getStart().getCharacter() == 0

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
      val (namedValuePrefix, value) = trimmed match {
        case NamedValueArgument(prefix, value) => (prefix, value.trim())
        case _ => ("", trimmed)
      }
      val isArray = value.startsWith("{") && value.endsWith("}")
      val arrayContents =
        if (isArray) value.substring(1, value.length() - 1).trim()
        else ""
      val (range, newText) =
        if (isArray && arrayContents.isEmpty()) {
          (
            new l.Range(
              text.indexToLspPosition(insideStart),
              text.indexToLspPosition(insideEnd),
            ),
            s"""$namedValuePrefix{"$warningName"}""",
          )
        } else if (isArray) {
          val closeBrace = insideStart + inside.lastIndexOf('}')
          val separator = if (arrayContents.endsWith(",")) " " else ", "
          (
            new l.Range(
              text.indexToLspPosition(closeBrace),
              text.indexToLspPosition(closeBrace),
            ),
            s"""$separator"$warningName"""",
          )
        } else {
          (
            new l.Range(
              text.indexToLspPosition(insideStart),
              text.indexToLspPosition(insideEnd),
            ),
            s"""$namedValuePrefix{$value, "$warningName"}""",
          )
        }
      Some(new l.TextEdit(range, newText))
    }
  }

  private val NamedValueArgument = """(?s)(value\s*=\s*)(.*)""".r

  private case class SuppressTarget(
      member: JavaMember,
      nameRange: JavaRange,
  )

  private case class ExistingSuppressWarnings(
      openParenOffset: Int,
      closeParenOffset: Int,
  )
}
