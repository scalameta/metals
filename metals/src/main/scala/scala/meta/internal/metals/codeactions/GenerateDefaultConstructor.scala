package scala.meta.internal.metals.codeactions

import javax.lang.model.element.Modifier

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaClassInfo
import scala.meta.internal.parsing.JavaMemberInfo
import scala.meta.internal.parsing.JavaMemberKind
import scala.meta.internal.parsing.JavaTrees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateDefaultConstructor(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {

  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val position = params.getRange().getStart()

    javaTrees.findEnclosingJavaClass(path, position).toSeq.collect {
      case cls
          if params.getRange().overlapsWith(cls.nameRange) &&
            !hasDefaultConstructor(cls) =>
        val insertRange = constructorInsertRange(cls)
        val edit = new l.TextEdit(
          insertRange,
          constructorText(
            path,
            cls.name,
            constructorModifier(cls),
            insertRange,
          ),
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

  private def constructorInsertRange(cls: JavaClassInfo): l.Range = {
    val firstMethodIndex = cls.members.indexWhere(member =>
      member.kind == JavaMemberKind.Method ||
        member.kind == JavaMemberKind.Constructor
    )
    val membersBeforeMethods =
      if (firstMethodIndex >= 0) cls.members.take(firstMethodIndex)
      else cls.members
    val lastFieldEnd = membersBeforeMethods.collect {
      case member if member.kind == JavaMemberKind.Field =>
        member.range.getEnd()
    }.lastOption

    val start = lastFieldEnd.getOrElse(cls.bodyRange.getStart())
    val end = nextMemberStart(cls.members, start).getOrElse(
      cls.bodyRange.getEnd()
    )
    new l.Range(start, end)
  }

  private def nextMemberStart(
      members: List[JavaMemberInfo],
      position: l.Position,
  ): Option[l.Position] = {
    members
      .map(_.range.getStart())
      .filter(pos =>
        pos.getLine() > position.getLine() ||
          (pos.getLine() == position.getLine() &&
            pos.getCharacter() > position.getCharacter())
      )
      .sortBy(pos => (pos.getLine(), pos.getCharacter()))
      .headOption
  }

  private def constructorModifier(cls: JavaClassInfo): String = {
    if (cls.modifiers.contains(Modifier.ABSTRACT)) "protected "
    else if (cls.modifiers.contains(Modifier.PUBLIC)) "public "
    else if (cls.modifiers.contains(Modifier.PROTECTED)) "protected "
    else if (cls.modifiers.contains(Modifier.PRIVATE)) "private "
    else ""
  }

  private def constructorText(
      path: AbsolutePath,
      className: String,
      modifier: String,
      insertRange: l.Range,
  ): String = {
    val text = buffers.get(path).getOrElse("")
    val input = Input.String(text)
    val startOffset =
      input.toOffset(
        insertRange.getStart().getLine(),
        insertRange.getStart().getCharacter(),
      )
    val endOffset =
      input.toOffset(
        insertRange.getEnd().getLine(),
        insertRange.getEnd().getCharacter(),
      )
    val endIndent = indentAt(text, endOffset)
    val memberIndent =
      if (endIndent.nonEmpty) endIndent
      else {
        val lineStart = text.lastIndexOf('\n', startOffset - 1) + 1
        val indent =
          text.substring(lineStart).takeWhile(c => c != '\n' && c.isWhitespace)
        if (indent.nonEmpty) indent else "  "
      }
    val prefix =
      if (startOffset > 0 && text.charAt(startOffset - 1) == '{') "\n"
      else "\n\n"
    val suffix =
      if (endOffset < text.length && text.charAt(endOffset) == '}')
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
}

object GenerateDefaultConstructor {
  def title(className: String): String =
    s"Generate default constructor for $className"
}
