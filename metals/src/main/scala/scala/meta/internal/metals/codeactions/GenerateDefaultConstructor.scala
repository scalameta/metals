package scala.meta.internal.metals.codeactions

import javax.lang.model.element.Modifier

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.GenerateDefaultConstructor.InsertPoint
import scala.meta.internal.metals.codeactions.GenerateDefaultConstructor.PositionWithOffset
import scala.meta.internal.parsing.JavaClassInfo
import scala.meta.internal.parsing.JavaMemberKind
import scala.meta.internal.parsing.JavaTrees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateDefaultConstructor(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val position = params.getRange().getStart()
    for {
      text <- buffers.get(path).toSeq
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
      if params.getRange().overlapsWith(cls.nameRange) &&
        !hasDefaultConstructor(cls)
    } yield {
      val insert = constructorInsertPoint(cls, text)
      val edit = new l.TextEdit(
        insert.range,
        constructorText(text, cls.name, constructorModifier(cls), insert),
      )
      CodeActionBuilder.build(
        title = GenerateDefaultConstructor.title(cls.name),
        kind = kind,
        changes = Seq(path -> Seq(edit)),
      )
    }
  }

  override def isScala: Boolean = false

  override def isJava: Boolean = true

  private def hasDefaultConstructor(cls: JavaClassInfo): Boolean =
    cls.members.exists(member =>
      member.kind == JavaMemberKind.Constructor &&
        member.parametersCount.contains(0)
    )

  private def constructorInsertPoint(
      cls: JavaClassInfo,
      text: String,
  ): InsertPoint = {
    val start = cls.members
      .takeWhile(_.kind == JavaMemberKind.Field)
      .lastOption
      .map(m => PositionWithOffset(m.range.getEnd(), m.range.endOffset))
      .getOrElse(
        PositionWithOffset(cls.bodyRange.getStart(), cls.bodyRange.startOffset)
      )

    val expandedEnd = cls.members
      .filter(_.range.startOffset > start.offset)
      .minByOption(_.range.startOffset)
      .map(m => PositionWithOffset(m.range.getStart(), m.range.startOffset))
      .getOrElse(
        PositionWithOffset(cls.bodyRange.getEnd(), cls.bodyRange.endOffset)
      )

    val canExpand =
      expandedEnd.offset <= text.length &&
        text.substring(start.offset, expandedEnd.offset).forall(_.isWhitespace)

    val end = if (canExpand) expandedEnd else start
    InsertPoint(
      new l.Range(start.position, end.position),
      start.offset,
      end.offset,
      isInsertion = !canExpand,
    )
  }

  private def constructorModifier(cls: JavaClassInfo): String = {
    if (cls.modifiers.contains(Modifier.ABSTRACT)) "protected "
    else if (cls.modifiers.contains(Modifier.PUBLIC)) "public "
    else if (cls.modifiers.contains(Modifier.PROTECTED)) "protected "
    else if (cls.modifiers.contains(Modifier.PRIVATE)) "private "
    else ""
  }

  private def constructorText(
      text: String,
      className: String,
      modifier: String,
      insert: InsertPoint,
  ): String = {
    val startOffset = insert.startOffset
    val endOffset = insert.endOffset
    val endIndent = indentAt(text, endOffset)
    val memberIndent = {
      val startLineIndent = lineIndent(text, startOffset)
      if (startLineIndent.nonEmpty) startLineIndent
      else if (endIndent.nonEmpty) endIndent
      else detectIndentUnit(text)
    }
    val prefix =
      if (startOffset > 0 && text.charAt(startOffset - 1) == '{') "\n"
      else "\n\n"
    val suffix =
      if (insert.isInsertion) ""
      else if (endOffset < text.length && text.charAt(endOffset) == '}')
        s"\n$endIndent"
      else s"\n\n$endIndent"

    s"$prefix$memberIndent$modifier$className() {\n$memberIndent}$suffix"
  }

  private def indentAt(text: String, offset: Int): String = {
    val clamped = offset.min(text.length).max(0)
    val lineStart = text.lastIndexOf('\n', clamped - 1) + 1
    val candidate = text.substring(lineStart, clamped)
    if (candidate.forall(_.isWhitespace)) candidate else ""
  }

  private def lineIndent(text: String, offset: Int): String = {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    text.substring(lineStart).takeWhile(c => c != '\n' && c.isWhitespace)
  }

  private def detectIndentUnit(text: String): String = {
    val firstIndent = text.linesIterator
      .map(_.takeWhile(c => c == ' ' || c == '\t'))
      .find(_.nonEmpty)
    firstIndent match {
      case Some(ws) if ws.startsWith("\t") => "\t"
      case _ => "  "
    }
  }
}

object GenerateDefaultConstructor {
  def title(className: String): String =
    s"Generate default constructor for $className"

  private case class InsertPoint(
      range: l.Range,
      startOffset: Int,
      endOffset: Int,
      isInsertion: Boolean,
  )

  private case class PositionWithOffset(position: l.Position, offset: Int)
}
