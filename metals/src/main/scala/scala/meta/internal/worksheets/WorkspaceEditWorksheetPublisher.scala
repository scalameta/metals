package scala.meta.internal.worksheets

import scala.meta.internal.metals.MetalsLanguageClient
import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.MarkedString
import scala.meta.internal.metals.MetalsEnrichments._
import mdoc.interfaces.EvaluatedWorksheetStatement
import scala.meta.inputs.Input
import org.eclipse.lsp4j.{Position, Range}
import scala.meta.internal.metals.Buffers
import org.eclipse.lsp4j.Hover
import scala.meta.internal.metals.TokenEditDistance
import WorkspaceEditWorksheetPublisher._

class WorkspaceEditWorksheetPublisher(buffers: Buffers)
    extends WorksheetPublisher {

  private var hoverMessages = Map.empty[AbsolutePath, HoverMap]

  override def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit = {
    val rendered = render(path, worksheet)
    publish(languageClient, path, rendered)
  }

  override def hover(path: AbsolutePath, position: Position): Option[Hover] = {
    for {
      messages <- hoverMessages.get(path)
      distance = TokenEditDistance.fromBuffer(
        path,
        messages.textSnapshot,
        buffers
      )
      snapshotPosition <- distance
        .toOriginal(position.getLine(), position.getCharacter())
        .toPosition(position)
      message <- getHoverMessage(snapshotPosition, messages.hovers)
    } yield new Hover(
      List(
        JEither.forRight[String, MarkedString](
          new MarkedString("scala", message)
        )
      ).asJava
    )
  }

  private def render(
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): RenderResult = {
    val source = path.toInputFromBuffers(buffers)
    val editsWithDetails =
      worksheet.statements.asScala
        .map(statement => renderEdit(statement, source))

    val edits =
      editsWithDetails.map(ed => new TextEdit(ed.range, ed.text)).toList
    val hovers = editsWithDetails.map(ed =>
      HoverMessage(
        ed.range
          .copy(
            endCharacter = ed.range.getStart.getCharacter + ed.text.length
          ),
        ed.details
      )
    )
    val hoverMap = HoverMap(updateWithEdits(source.text, edits), hovers)

    RenderResult(edits, hoverMap)
  }

  private def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      rendered: RenderResult
  ): Unit = {
    hoverMessages = hoverMessages.updated(path, rendered.hovers)

    val params = new ApplyWorkspaceEditParams(
      new WorkspaceEdit(
        Map(path.toURI.toString -> (rendered.edits.asJava)).asJava
      )
    )
    languageClient.applyEdit(params)
  }

  private def renderEdit(
      statement: EvaluatedWorksheetStatement,
      source: Input
  ): RenderEditResult = {
    val startPosition =
      new Position(statement.position.endLine, statement.position.endColumn)
    val endPosition =
      locatePreviousEdit(statement, source).getOrElse(startPosition)

    RenderEditResult(
      new Range(startPosition, endPosition),
      renderMessage(statement),
      statement.details()
    )
  }

  private def renderMessage(statement: EvaluatedWorksheetStatement): String = {
    import statement._
    val out = new StringBuilder()
    out.append("  /*>  ")
    out.append(summary())
    out.append(if (!isSummaryComplete()) "..." else "")
    out.append("  */")
    out.result()
  }

  private def locatePreviousEdit(
      statement: EvaluatedWorksheetStatement,
      source: Input
  ): Option[Position] = {
    val editPattern = """\A\s*/\*>.*?\*/""".r
    val offset = source.lineToOffset(statement.position.endLine) + statement.position.endColumn
    val text = source.text.drop(offset)
    editPattern
      .findFirstMatchIn(text)
      .map(m => {
        val p = source.toOffsetPosition(offset + m.end)
        new Position(p.endLine, p.endColumn)
      })
  }

  private def getHoverMessage(
      position: Position,
      hovers: Seq[HoverMessage]
  ): Option[String] = {
    hovers.find(_.range.encloses(position)).map(_.message)
  }

  private def updateWithEdits(text: String, edits: List[TextEdit]): String = {
    val editsMap = edits.map(e => e.getRange().getStart().getLine() -> e).toMap

    text.linesIterator.zipWithIndex
      .map {
        case (line, i) =>
          editsMap.get(i) match {
            case Some(edit) =>
              val before =
                line.substring(0, edit.getRange.getStart.getCharacter)
              val after = line.substring(edit.getRange.getEnd.getCharacter)
              before + edit.getNewText() + after
            case None => line
          }
      }
      .mkString("\n")
  }

}

object WorkspaceEditWorksheetPublisher {

  case class HoverMessage(range: Range, message: String)
  case class HoverMap(textSnapshot: String, hovers: Seq[HoverMessage])

  case class RenderEditResult(range: Range, text: String, details: String)
  case class RenderResult(edits: List[TextEdit], hovers: HoverMap)

}
