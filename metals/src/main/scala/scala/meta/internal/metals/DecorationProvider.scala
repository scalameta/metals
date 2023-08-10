package scala.meta.internal.metals

import scala.meta.internal.decorations._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.InlayHintPart
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

final class DecorationProvider(
    params: VirtualFileParams,
    trees: Trees,
    userConfig: () => UserConfiguration,
) extends SyntheticDecorationsProvider[DecorationOptions](
      params,
      trees,
      userConfig,
    ) {
  def makeDecoration(
      pos: l.Position,
      labelParts: List[InlayHintPart],
      kind: l.InlayHintKind,
      addTextEdit: Boolean = false,
  ): DecorationOptions = {
    val decorationText = makeDecorationText(labelParts)
    val lspRange = new l.Range(pos, pos)
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          decorationText,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7,
        )
      ),
    )
  }

  private def makeDecorationText(
      labelParts: List[InlayHintPart],
  ): String = {
    labelParts.map(_.label()).mkString
  }
}
