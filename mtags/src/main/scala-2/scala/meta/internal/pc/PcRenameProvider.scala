package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.TextEdit

class PcRenameProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams,
    name: String
) extends PcCollector[TextEdit](compiler, params) {
  import compiler._
  val newName: String = Identifier.backtickWrap(name.stripBackticks)
  def collect(tree: Tree, toAdjust: Position): TextEdit = {
    val (pos, stripBackticks) = adjust(toAdjust)
    new TextEdit(
      pos.toLsp,
      if (stripBackticks) newName.stripBackticks else newName
    )
  }

  def rename(): List[TextEdit] =
    result()

}
