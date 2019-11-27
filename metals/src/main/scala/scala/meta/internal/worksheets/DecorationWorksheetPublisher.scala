package scala.meta.internal.worksheets

import scala.meta.internal.metals.MetalsLanguageClient
import mdoc.interfaces.EvaluatedWorksheet
import scala.meta.internal.decorations.DecorationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.decorations.ThemableDecorationInstanceRenderOptions
import scala.meta.internal.decorations.ThemableDecorationAttachmentRenderOptions
import MdocToLspUtils._
import org.eclipse.lsp4j.MarkedString
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.io.AbsolutePath

class DecorationWorksheetPublisher() extends WorksheetPublisher {

  override def publish(
      languageClient: MetalsLanguageClient,
      path: AbsolutePath,
      worksheet: EvaluatedWorksheet
  ): Unit = {
    (render _ andThen publish(languageClient, path))(worksheet)
  }

  private def render(
      worksheet: EvaluatedWorksheet
  ): Array[DecorationOptions] = {
    worksheet
      .statements()
      .iterator()
      .asScala
      .map { s =>
        new DecorationOptions(
          toLsp(s.position()),
          new MarkedString("scala", s.details()),
          ThemableDecorationInstanceRenderOptions(
            after = ThemableDecorationAttachmentRenderOptions(
              s.summary(),
              color = "green",
              fontStyle = "italic"
            )
          )
        )
      }
      .toArray
  }

  private def publish(languageClient: MetalsLanguageClient, path: AbsolutePath)(
      decorations: Array[DecorationOptions]
  ): Unit = {
    val params =
      new PublishDecorationsParams(path.toURI.toString(), decorations)
    languageClient.metalsPublishDecorations(params)
  }

}
