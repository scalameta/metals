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
    tree match {
      case id @ Ident(_) if id.isBackquoted =>
        new TextEdit(pos.toLsp, '`' + newName.stripBackticks + '`')
      case _ =>
        new TextEdit(pos.toLsp, newName)
    }
  }

  def rename(): List[TextEdit] =
    result()

}
