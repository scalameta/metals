package scala.meta.internal.worksheets

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.pc.HoverMarkup
import scala.meta.internal.worksheets.MdocEnrichments.truncatify
import scala.meta.internal.worksheets.WorkspaceEditWorksheetPublisher._
import scala.meta.io.AbsolutePath

import mdoc.interfaces.EvaluatedWorksheet
import mdoc.interfaces.EvaluatedWorksheetStatement
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

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
      distance = buffers.tokenEditDistance(
        path,
        messages.textSnapshot
      )
      snapshotPosition <-
        distance
          .toOriginal(position.getLine(), position.getCharacter())
          .toPosition(position)
      message <- getHoverMessage(snapshotPosition, messages.hovers)
    } yield new Hover(
      new MarkupContent(
        MarkupKind.MARKDOWN,
        HoverMarkup(message)
      )
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
    val out = new StringBuilder()
    out.append("  /*>  ")
    out.append(truncatify(statement))
    out.append("  */")
    out.result()
  }

  private def locatePreviousEdit(
      statement: EvaluatedWorksheetStatement,
      source: Input
  ): Option[Position] = {
    val editPattern = """\A\s*/\*>.*?\*/""".r
    val offset =
      source.lineToOffset(
        statement.position.endLine
      ) + statement.position.endColumn
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
