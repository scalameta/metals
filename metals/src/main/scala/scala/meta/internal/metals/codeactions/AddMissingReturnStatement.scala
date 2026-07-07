package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class AddMissingReturnStatement(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import AddMissingReturnStatement._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val position = range.getStart()
    val diagnostics = params.getContext().getDiagnostics().asScala.toList

    val actions = for {
      text <- buffers.get(path).toSeq
      method <- javaTrees.findEnclosingJavaMethod(path, position).toSeq
      if hasMissingReturnDiagnostic(diagnostics, method)
    } yield {
      val edit = returnEdit(text, method)
      CodeActionBuilder.build(
        title,
        kind,
        changes = Seq(path -> Seq(edit)),
        diagnostics = diagnostics.filter(diagnostic =>
          isMissingReturnDiagnostic(diagnostic, method)
        ),
      )
    }

    actions
  }

  private def returnEdit(
      text: String,
      method: JavaMethod,
  ): l.TextEdit = {
    val insert = JavaTrees.insertPointAtBodyEnd(method.bodyRange, text)
    val rendered =
      JavaMemberInsertion.render(
        text,
        insert,
        Seq(returnStatement(method.returnType)),
      )

    new l.TextEdit(
      insert.range,
      rendered,
    )
  }

  private def isMissingReturnDiagnostic(
      diagnostic: l.Diagnostic,
      method: JavaMethod,
  ): Boolean =
    hasMissingReturnMessage(diagnostic) &&
      (diagnostic.getRange().overlapsWith(method.range.range) ||
        diagnostic.getRange().getStart() == method.range.range.getEnd())

  private def hasMissingReturnDiagnostic(
      diagnostics: List[l.Diagnostic],
      method: JavaMethod,
  ): Boolean =
    diagnostics.exists(isMissingReturnDiagnostic(_, method))
}

object AddMissingReturnStatement {
  def title: String = "Add missing return statement"

  def hasMissingReturnMessage(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getMessage())
      .map(_.toString().toLowerCase())
      .exists(_.contains("missing return"))

  private def returnStatement(returnType: String): String =
    s"return ${defaultReturnValue(returnType)};"

  private def defaultReturnValue(returnType: String): String =
    returnType match {
      case "boolean" => "false"
      case "byte" | "short" | "int" => "0"
      case "long" => "0L"
      case "float" => "0.0f"
      case "double" => "0.0"
      case "char" => "'\\u0000'"
      case _ => "null"
    }
}
