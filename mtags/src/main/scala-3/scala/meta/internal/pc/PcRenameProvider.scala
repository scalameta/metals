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
  val newName = name.backticked

  def collect(tree: Tree, pos: SourcePosition): l.TextEdit =
    pprint.log(tree)
    tree match
      case id @ Ident(_) if id.isBackquoted =>
        pprint.log(id.isBackquoted)
        l.TextEdit(pos.toLsp, '`' + newName.stripBackticks + '`')
      case _ =>
        l.TextEdit(pos.toLsp, newName)

  def rename(
  ): List[l.TextEdit] =
    result()

end PcRenameProvider
