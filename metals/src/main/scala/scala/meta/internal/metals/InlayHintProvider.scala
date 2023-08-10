package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.InlayHintPart
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

final class InlayHintProvider(
    params: VirtualFileParams,
    trees: Trees,
    userConfig: () => UserConfiguration,
) extends SyntheticDecorationsProvider[l.InlayHint](
      params,
      trees,
      userConfig,
    ) {
  override def makeDecoration(
      pos: l.Position,
      labelParts: List[InlayHintPart],
      kind: l.InlayHintKind,
      addTextEdit: Boolean = false,
  ): l.InlayHint = {
    val hint = new l.InlayHint()
    hint.setPosition(pos)
    val (label, data) =
      labelParts.map { lp =>
        val labelPart = new l.InlayHintLabelPart()
        labelPart.setValue(lp.label())
        (labelPart, lp.symbol())
      }.unzip
    hint.setLabel(label.asJava)
    hint.setData(data.asJava)
    hint.setKind(kind)
    if (addTextEdit) {
      val textEdit = new l.TextEdit()
      textEdit.setRange(new l.Range(pos, pos))
      textEdit.setNewText(labelParts.map(_.label()).mkString)
      hint.setTextEdits(List(textEdit).asJava)
    }
    hint
  }
}
