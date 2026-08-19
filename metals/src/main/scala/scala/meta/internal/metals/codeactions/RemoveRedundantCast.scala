package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.JavaTypeCast
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class RemoveRedundantCast(javaTrees: JavaTrees, buffers: Buffers)
    extends CodeAction {
  import RemoveRedundantCast._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    for {
      text <- buffers.get(path).orElse(path.readTextOpt).toSeq
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      if isRedundantCast(diagnostic)
      if range.overlapsWith(diagnostic.getRange())
      cast <- javaTrees
        .findTypeCast(path, diagnostic.getRange().getStart())
        .toSeq
    } yield CodeActionBuilder.build(
      title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(removeCastEdit(text, cast))),
    )
  }
}

object RemoveRedundantCast {
  val title = "Remove redundant cast"

  private val RedundantCastCode = "compiler.warn.redundant.cast"

  private def isRedundantCast(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getCode()).exists(code =>
      code.isLeft() && code.getLeft() == RedundantCastCode
    )

  private def removeCastEdit(text: String, cast: JavaTypeCast): l.TextEdit = {
    val castStart = cast.typeRange.startOffset
    val typeEnd = cast.typeRange.endOffset
    val editEnd = typeEnd + text
      .substring(typeEnd, cast.exprRange.startOffset)
      .takeWhile(ch => ch == ' ' || ch == '\t')
      .length
    val editStart =
      if (
        editEnd >= text.length || text.charAt(editEnd) == '\n' || text
          .charAt(editEnd) == '\r'
      )
        castStart - JavaMemberInsertion
          .linePrefix(text, castStart)
          .reverse
          .takeWhile(ch => ch == ' ' || ch == '\t')
          .length
      else
        castStart
    new l.TextEdit(
      new l.Range(
        text.indexToLspPosition(editStart),
        text.indexToLspPosition(editEnd),
      ),
      "",
    )
  }

}
