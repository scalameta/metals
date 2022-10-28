package scala.meta.internal.pc
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}
final class PcRenameProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    name: String,
) extends PcCollector[l.TextEdit](driver, params):
  val newName = name.stripBackticks.backticked

  def collect(tree: Tree, pos: SourcePosition): l.TextEdit =
    val isBackticked =
      sourceText(pos.start) == '`' && sourceText(pos.end - 1) == '`'
    // when the old name contains backticks, the position is incorrect
    val isOldNameBackticked = sourceText(pos.start) != '`' &&
      sourceText(pos.start - 1) == '`' &&
      sourceText(pos.end) == '`'

    if isBackticked then
      l.TextEdit(pos.toLsp, "`" + newName.stripBackticks + "`")
    else if isOldNameBackticked then
      l.TextEdit(
        pos.withStart(pos.start - 1).withEnd(pos.end + 1).toLsp,
        newName,
      )
    else l.TextEdit(pos.toLsp, newName)
  end collect

  def rename(
  ): List[l.TextEdit] =
    result()

end PcRenameProvider
