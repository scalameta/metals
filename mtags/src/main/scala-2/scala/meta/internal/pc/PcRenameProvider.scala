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

  def collect(tree: Tree, pos: Position): TextEdit = {
    val isBackticked = text(pos.start) == '`' && text(pos.end - 1) == '`'
    // val oldNameBackticked = tree.symbol.decodedName.isBackticked
    val backtickedName =
      if (isBackticked) "`" + newName.stripBackticks + "`"
      else newName
    new TextEdit(pos.toLsp, backtickedName)
  }

  def rename(): List[TextEdit] =
    result()

}
