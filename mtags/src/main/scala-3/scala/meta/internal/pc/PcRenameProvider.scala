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

  def collect(tree: Tree, toAdjust: SourcePosition): l.TextEdit =
    val (pos, stripBackticks) = adjust(toAdjust)
    l.TextEdit(
      pos.toLsp,
      if stripBackticks then newName.stripBackticks else newName,
    )
  end collect

  def rename(
  ): List[l.TextEdit] =
    result()

end PcRenameProvider
