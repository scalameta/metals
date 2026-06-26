package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.jpc.MissingOverrideDiagnosticProvider
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class AddMissingOverrideAnnotation(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import AddMissingOverrideAnnotation._

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
      if isMissingOverride(diagnostic)
      if range.overlapsWith(diagnostic.getRange())
      method <- javaTrees
        .findEnclosingJavaMethod(path, diagnostic.getRange().getStart())
        .toSeq
      if !method.isConstructor
    } yield build(path, diagnostic, method, text)
  }

  private def build(
      path: AbsolutePath,
      diagnostic: l.Diagnostic,
      method: JavaMethod,
      text: String,
  ): l.CodeAction = {
    val methodStart = method.range.getStart()
    val position = new l.Position(methodStart.getLine(), 0)
    val edit = new l.TextEdit(
      new l.Range(position, position),
      s"${indentAt(text, method.range.startOffset)}@Override\n",
    )
    CodeActionBuilder.build(
      title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
  }

  private def indentAt(text: String, offset: Int): String = {
    val lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1
    text
      .slice(lineStart, Math.min(offset, text.length))
      .takeWhile(ch => ch == ' ' || ch == '\t')
  }
}

object AddMissingOverrideAnnotation {
  val title = "Add missing @Override annotation"

  private def isMissingOverride(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getCode()).exists(code =>
      code.isLeft() &&
        code.getLeft() == MissingOverrideDiagnosticProvider.MissingOverrideCode
    )
}
