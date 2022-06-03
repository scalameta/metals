package scala.meta.internal.worksheets

import scala.meta.internal.decorations.DecorationOptions
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.decorations.ThemableDecorationAttachmentRenderOptions
import scala.meta.internal.decorations.ThemableDecorationInstanceRenderOptions
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.pc.HoverMarkup
import scala.meta.internal.worksheets.MdocEnrichments._
import scala.meta.io.AbsolutePath

import mdoc.interfaces.EvaluatedWorksheet
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position

class DecorationWorksheetPublisher(isInlineDecorationProvider: Boolean)
    extends WorksheetPublisher {

  private val commentHeader = " // "

  override def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet,
  ): Unit = {
    val rendered = render(worksheet)
    publish(languageClient, path, rendered)
  }

  override def hover(path: AbsolutePath, position: Position): Option[Hover] =
    // publish'ed Decorations handle hover, so nothing to return here
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
            HoverMarkup(s.prettyDetails()),
          ),
          ThemableDecorationInstanceRenderOptions(
            after = ThemableDecorationAttachmentRenderOptions(
              commentHeader + truncatify(s),
              color = "green",
              fontStyle = "italic",
            )
          ),
        )
      }
      .toArray
  }

  private def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      decorations: Array[DecorationOptions],
  ): Unit = {
    val params =
      new PublishDecorationsParams(
        path.toURI.toString(),
        decorations,
        // do not send additional param if it's not inline provider
        isInline = if (isInlineDecorationProvider) false else null,
      )
    languageClient.metalsPublishDecorations(params)
  }

}
