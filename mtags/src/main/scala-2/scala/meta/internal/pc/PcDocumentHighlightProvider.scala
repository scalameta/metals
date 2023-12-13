package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind

final class PcDocumentHighlightProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams
) extends WithSymbolSearchCollector[DocumentHighlight](compiler, params) {
  import compiler._

  def collect(
      parent: Option[Tree]
  )(tree: Tree, toAdjust: Position, sym: Option[Symbol]): DocumentHighlight = {
    val (pos, _) = toAdjust.adjust(text)
    tree match {
      case _: MemberDef =>
        new DocumentHighlight(pos.toLsp, DocumentHighlightKind.Write)
      case _: Bind =>
        new DocumentHighlight(pos.toLsp, DocumentHighlightKind.Write)
      case _ =>
        new DocumentHighlight(pos.toLsp, DocumentHighlightKind.Read)
    }
  }

  def highlights(): List[DocumentHighlight] =
    result()
}
