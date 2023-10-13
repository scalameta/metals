package scala.meta.internal.decorations

import javax.annotation.Nullable

import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

trait DecorationClient {
  @JsonNotification("metals/publishDecorations")
  def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit
}

case class DecorationOptions(
    range: Range,
    @Nullable hoverMessage: MarkupContent = null,
    @Nullable renderOptions: ThemableDecorationInstanceRenderOptions = null,
)

object DecorationOptions {
  def apply(range: Range, text: String) =
    new DecorationOptions(
      range,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          text,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7,
        )
      ),
    )
}

case class PublishDecorationsParams(
    uri: String,
    options: Array[DecorationOptions],
    @Nullable isInline: java.lang.Boolean,
)
