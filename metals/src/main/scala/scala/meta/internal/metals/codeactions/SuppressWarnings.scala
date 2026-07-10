package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
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
      member <- enclosingMember(path, position).toSeq
      edit <- suppressEdit(text, member, warningName).toSeq
    } yield CodeActionBuilder.build(
      title(warningName),
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
    actions.distinctBy(_.getTitle())
  }

  private def enclosingMember(
      path: AbsolutePath,
      position: l.Position,
  ): Option[SuppressTarget] =
    javaTrees
      .findEnclosingJavaVariable(path, position, onNameOnly = false)
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

object SuppressWarnings {
  def title(warningName: String): String =
    s"""Add @SuppressWarnings("$warningName")"""

  private val SuppressWarningsName = "@SuppressWarnings"

  private val WarningNames =
    List(
      "auxiliaryclass", "cast", "classfile", "deprecation", "dep-ann",
      "divzero", "empty", "exports", "fallthrough", "finally",
      "lossy-conversions", "missing-explicit-ctor", "module", "opens",
      "options", "output-file-clash", "overloads", "overrides", "path",
      "processing", "rawtypes", "removal", "requires-automatic",
      "requires-transitive-automatic", "serial", "static", "strictfp",
      "synchronization", "text-blocks", "this-escape", "try", "unchecked",
      "varargs", "preview",
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

  private def warningNameFrom(text: String): Option[String] = {
    val matched = WarningNames.filter(name => containsWholeWord(text, name))
    matched.maxByOption(_.length).orElse {
      if (text.contains("missing.deprecated.annotation")) Some("dep-ann")
      else if (text.contains("loss.of.precision")) Some("lossy-conversions")
      else if (text.contains("fall-through")) Some("fallthrough")
      else if (text.contains("ambiguous.overload")) Some("overloads")
      else if (text.contains("override.equals")) Some("overrides")
      else if (text.contains("trailing.white.space")) Some("text-blocks")
      else if (text.contains("this.escape")) Some("this-escape")
      else if (text.contains("synchronize")) Some("synchronization")
      else if (text.contains("deprecated")) Some("deprecation")
      else if (text.contains("raw.class")) Some("rawtypes")
      else if (text.contains("serialversionuid") || text.contains("svuid"))
        Some("serial")
      else None
    }
  }

  private def containsWholeWord(text: String, name: String): Boolean = {
    var index = text.indexOf(name)
    var found = false
    while (!found && index >= 0) {
      val end = index + name.length
      val beforeOk = index == 0 || !isWarningNameChar(text.charAt(index - 1))
      val afterOk = end >= text.length || !isWarningNameChar(text.charAt(end))
      if (beforeOk && afterOk) found = true
      else index = text.indexOf(name, index + 1)
    }
    found
  }

  private def isWarningNameChar(ch: Char): Boolean =
    Character.isLetterOrDigit(ch) || ch == '-'

  private def isZeroRange(range: l.Range): Boolean =
    range.getStart().getLine() == 0 &&
      range.getStart().getCharacter() == 0 &&
      range.getEnd().getLine() == 0 &&
      range.getEnd().getCharacter() == 0

  private def suppressEdit(
      text: String,
      target: SuppressTarget,
      warningName: String,
  ): Option[l.TextEdit] =
    existingSuppressWarnings(text, target) match {
      case Some(existing) => appendWarningEdit(text, existing, warningName)
      case None => Some(insertSuppressWarningsEdit(text, target, warningName))
    }

  private def insertSuppressWarningsEdit(
      text: String,
      target: SuppressTarget,
      warningName: String,
  ): l.TextEdit = {
    val declarationOffset = declarationStartOffset(text, target)
    val declarationStart = positionAtOffset(text, declarationOffset)
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

  /**
   * The offset where the member declaration proper starts, skipping any
   * annotations that precede it, so that `@SuppressWarnings` lands after
   * existing annotations like `@Override`.
   */
  private def declarationStartOffset(
      text: String,
      target: SuppressTarget,
  ): Int = {
    val end = target.nameRange.startOffset.min(text.length)
    var offset = target.member.range.startOffset.max(0)
    var continue = true
    while (continue) {
      var annotationStart = offset
      while (annotationStart < end && text.charAt(annotationStart).isWhitespace)
        annotationStart += 1
      if (annotationStart < end && text.charAt(annotationStart) == '@') {
        var nameEnd = annotationStart + 1
        while (
          nameEnd < end &&
          (Character.isJavaIdentifierPart(text.charAt(nameEnd)) ||
            text.charAt(nameEnd) == '.')
        ) nameEnd += 1
        // `@interface` is an annotation-type declaration, not an annotation.
        if (text.substring(annotationStart + 1, nameEnd) == "interface")
          continue = false
        else {
          var argsStart = nameEnd
          while (argsStart < end && text.charAt(argsStart).isWhitespace)
            argsStart += 1
          if (argsStart < end && text.charAt(argsStart) == '(')
            matchingCloseParen(text, argsStart, end) match {
              case Some(close) => offset = close + 1
              case None => continue = false
            }
          else offset = nameEnd
        }
      } else continue = false
    }
    var declarationStart = offset
    while (declarationStart < end && text.charAt(declarationStart).isWhitespace)
      declarationStart += 1
    declarationStart
  }

  private def existingSuppressWarnings(
      text: String,
      target: SuppressTarget,
  ): Option[ExistingSuppressWarnings] = {
    val prefixStart = target.member.range.startOffset
    val prefixEnd = target.nameRange.startOffset
    if (prefixStart < 0 || prefixEnd <= prefixStart || prefixEnd > text.length)
      None
    else {
      val prefix = text.substring(prefixStart, prefixEnd)
      val annotationStartInPrefix = prefix.indexOf(SuppressWarningsName)
      if (annotationStartInPrefix < 0) None
      else {
        val annotationStart = prefixStart + annotationStartInPrefix
        val open = text.indexOf('(', annotationStart)
        if (open < 0 || open >= prefixEnd) None
        else
          matchingCloseParen(text, open, prefixEnd).map { close =>
            ExistingSuppressWarnings(open, close)
          }
      }
    }
  }

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
              positionAtOffset(text, closeBrace),
              positionAtOffset(text, closeBrace),
            ),
            s""", "$warningName"""",
          )
        } else {
          (
            new l.Range(
              positionAtOffset(text, insideStart),
              positionAtOffset(text, insideEnd),
            ),
            s"""{$trimmed, "$warningName"}""",
          )
        }
      Some(new l.TextEdit(range, newText))
    }
  }

  private def matchingCloseParen(
      text: String,
      open: Int,
      end: Int,
  ): Option[Int] = {
    var offset = open
    var depth = 0
    while (offset < end) {
      text.charAt(offset) match {
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if (depth == 0) return Some(offset)
        case _ =>
      }
      offset += 1
    }
    None
  }

  private def positionAtOffset(text: String, offset: Int): l.Position = {
    val safeOffset = offset.max(0).min(text.length)
    var line = 0
    var lineStart = 0
    var index = 0
    while (index < safeOffset) {
      if (text.charAt(index) == '\n') {
        line += 1
        lineStart = index + 1
      }
      index += 1
    }
    new l.Position(line, safeOffset - lineStart)
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
