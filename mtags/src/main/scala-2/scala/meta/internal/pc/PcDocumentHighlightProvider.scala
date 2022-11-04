package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind

final class PcDocumentHighlightProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams
) extends PcCollector[DocumentHighlight](compiler, params) {
  import compiler._

  def collect(tree: Tree, toAdjust: Position): DocumentHighlight = {
    val (pos, _) = adjust(toAdjust, forHighlight = true)
    tree match {
      case _: MemberDef =>
        new DocumentHighlight(pos.toLsp, DocumentHighlightKind.Write)
      case _ =>
        new DocumentHighlight(pos.toLsp, DocumentHighlightKind.Read)
    }
  }

  def highlights(): List[DocumentHighlight] =
    result()
}
