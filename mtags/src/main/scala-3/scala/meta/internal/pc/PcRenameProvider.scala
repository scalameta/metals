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
    // val oldNameBackticked = tree.symbol.decodedName.isBackticked
    val backtickedName =
      if isBackticked then "`" + newName.stripBackticks + "`"
      else newName
    l.TextEdit(pos.toLsp, backtickedName)

  def rename(
  ): List[l.TextEdit] =
    result()

end PcRenameProvider
