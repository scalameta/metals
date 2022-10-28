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
    // when the old name contains backticks, the position is incorrect
    val isOldNameBackticked = text(pos.start) == '`' &&
      text(pos.end - 1) != '`' &&
      text(pos.end + 1) == '`'
    if (isBackticked)
      new TextEdit(pos.toLsp, "`" + newName.stripBackticks + "`")
    else if (isOldNameBackticked)
      new TextEdit(pos.withEnd(pos.end + 2).toLsp, newName)
    else new TextEdit(pos.toLsp, newName)
  }

  def rename(): List[TextEdit] =
    result()

}
