package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.JavaTrees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class JavaExtractMethodCodeAction(
    javaTrees: JavaTrees,
    compilers: Compilers,
) extends CodeAction {

  private case class ExtractMethodData(
      param: l.TextDocumentIdentifier,
      range: l.Range,
      extractPosition: l.Position,
  ) extends CodeActionResolveData

  override def kind: String = l.CodeActionKind.RefactorExtract

  override def isScala: Boolean = false

  override def isJava: Boolean = true

  override def resolveCodeAction(codeAction: l.CodeAction, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Option[Future[l.CodeAction]] = {
    parseData[ExtractMethodData](codeAction) match {
      case Some(data) =>
        val doc = data.param
        val uri = doc.getUri()
        val modifiedCodeAction = for {
          edits <- compilers.extractMethod(
            doc,
            data.range,
            data.extractPosition,
            token,
          )
          _ = logging.logErrorWhen(
            edits.isEmpty(),
            s"Could not extract method from range \n${data.range}\nin file ${uri.toAbsolutePath}",
          )
          workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
          _ = codeAction.setEdit(workspaceEdit)
        } yield codeAction
        Some(modifiedCodeAction)
      case _ =>
        None
    }
  }

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    if (range.getStart() == range.getEnd()) {
      Nil
    } else {
      for {
        method <- javaTrees
          .findEnclosingJavaMethod(path, range.getStart())
          .toSeq
        if range.overlapsWith(method.range.range)
      } yield {
        val data = ExtractMethodData(
          params.getTextDocument(),
          range,
          method.range.getStart(),
        )
        CodeActionBuilder.build(
          title = JavaExtractMethodCodeAction.title(method.name),
          kind = this.kind,
          data = Some(data.toJsonObject),
        )
      }
    }
  }
}

object JavaExtractMethodCodeAction {
  def title(methodName: String): String =
    s"Extract selection as method before `$methodName`"
}
