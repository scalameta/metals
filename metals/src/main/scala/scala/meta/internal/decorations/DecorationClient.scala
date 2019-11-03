package scala.meta.internal.decorations

import javax.annotation.Nullable
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.MarkedString

trait DecorationClient {
  @JsonNotification("metals/decorationTypeDidChange")
  def metalsDecorationTypeDidChange(
      params: DecorationTypeDidChange
  ): Unit
  @JsonNotification("metals/publishDecorations")
  def metalsDecorationRangesDidChange(
      params: PublishDecorationsParams
  ): Unit
}

case class DecorationTypeDidChange(
    @Nullable wholeLine: java.lang.Boolean = null,
    @Nullable rangeBehavior: java.lang.Integer = null,
    @Nullable overviewRulerLane: java.lang.Integer = null
)

case class DecorationOptions(
    range: Range,
    @Nullable hoverMessage: MarkedString = null,
    @Nullable renderOptions: ThemableDecorationInstanceRenderOptions = null
)

case class PublishDecorationsParams(
    uri: String,
    options: Array[DecorationOptions]
)
