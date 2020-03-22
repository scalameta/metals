package scala.meta.internal.decorations

import javax.annotation.Nullable
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Range

trait DecorationClient {
  @JsonNotification("metals/publishDecorations")
  def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit
}

case class DecorationOptions(
    range: Range,
    @Nullable hoverMessage: MarkupContent = null,
    @Nullable renderOptions: ThemableDecorationInstanceRenderOptions = null
)

case class PublishDecorationsParams(
    uri: String,
    options: Array[DecorationOptions]
)
