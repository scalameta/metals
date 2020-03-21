package scala.meta.internal.worksheets

import scala.meta.internal.metals.MetalsLanguageClient
import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.internal.decorations.DecorationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.decorations.ThemableDecorationInstanceRenderOptions
import scala.meta.internal.decorations.ThemableDecorationAttachmentRenderOptions
import MdocEnrichments._
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

class DecorationWorksheetPublisher() extends WorksheetPublisher {

  private val scalaMarkdownTics = "```scala"
  private val endTics = "```"
  private val commentHeader = " // "

  override def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit = {
    val rendered = render(worksheet)
    publish(languageClient, path, rendered)
  }

  override def hover(path: AbsolutePath, position: Position): Option[Hover] =
    //publish'ed Decorations handle hover, so nothing to return here
    None

  private def render(
      worksheet: EvaluatedWorksheet
  ): Array[DecorationOptions] = {
    worksheet
      .statements()
      .iterator()
      .asScala
      .map { s =>
        new DecorationOptions(
          s.position().toLsp,
          new MarkupContent(
            MarkupKind.MARKDOWN,
            List(scalaMarkdownTics, s.details, endTics).mkString("\n")
          ),
          ThemableDecorationInstanceRenderOptions(
            after = ThemableDecorationAttachmentRenderOptions(
              commentHeader + s.summary(),
              color = "green",
              fontStyle = "italic"
            )
          )
        )
      }
      .toArray
  }

  private def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      decorations: Array[DecorationOptions]
  ): Unit = {
    val params =
      new PublishDecorationsParams(path.toURI.toString(), decorations)
    languageClient.metalsPublishDecorations(params)
  }

}
