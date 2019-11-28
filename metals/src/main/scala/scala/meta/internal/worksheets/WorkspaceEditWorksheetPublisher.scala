package scala.meta.internal.worksheets

import scala.meta.internal.metals.MetalsLanguageClient
import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.TextEdit
import scala.meta.internal.metals.MetalsEnrichments._
import mdoc.interfaces.EvaluatedWorksheetStatement
import scala.meta.inputs.Input
import org.eclipse.lsp4j.{Position, Range}
import scala.meta.internal.metals.Buffers

class WorkspaceEditWorksheetPublisher(buffers: Buffers) extends WorksheetPublisher {

  override def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit = {
    (render(path) _ andThen publish(languageClient))(worksheet)
  }

  private def render(
      path: AbsolutePath
  )(worksheet: EvaluatedWorksheet): WorkspaceEdit = {
    val source = path.toInputFromBuffers(buffers)
    val edits =
      worksheet.statements.asScala
        .map(renderEdit(_, source))

    new WorkspaceEdit(
      Map(path.toURI.toString -> (edits.asJava)).asJava
    )
  }

  private def publish(
      languageClient: MetalsLanguageClient
  )(edit: WorkspaceEdit): Unit = {
    val params = new ApplyWorkspaceEditParams(edit)
    languageClient.applyEdit(params)
  }

  private def renderEdit(
      statement: EvaluatedWorksheetStatement,
      source: Input
  ): TextEdit = {
    val startPosition =
      new Position(statement.position.endLine, statement.position.endColumn)
    val endPosition =
      locatePreviousEdit(statement, source).getOrElse(startPosition)
    //trim to fix empty new line at start from Mdoc
    val alignedMessage =
      alignMessage(statement.details().trim(), statement.position.endColumn)

    new TextEdit(
      new Range(startPosition, endPosition),
      alignedMessage
    )
  }

  private def alignMessage(message: String, statementEndColumn: Int): String = {
    val messageOpening = "  /*>  "
    val messageEnding = "  */"
    val intendation = List.fill(statementEndColumn)(" ").reduce(_ + _) + "   *   "
    messageOpening +
      message.split("\n").mkString("\n" + intendation) +
      messageEnding
  }

  private def locatePreviousEdit(
      statement: EvaluatedWorksheetStatement,
      source: Input
  ): Option[Position] = {
    val editPattern = """\A\s*/\*>[\S\s]*?\*/""".r
    val offset = source.lineToOffset(statement.position.endLine) + statement.position.endColumn
    val text = source.text.drop(offset)
    editPattern
      .findFirstMatchIn(text)
      .map(m => {
        val p = source.toOffsetPosition(offset + m.end)
        new Position(p.endLine, p.endColumn)
      })
  }

}
