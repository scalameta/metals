package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

class PcRenameProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams,
    name: Option[String]
) extends PcCollector[l.TextEdit](compiler, params) {
  import compiler._
  private val forbiddenMethods =
    Set("equals", "hashCode", "unapply", "unary_!", "!")

  def canRenameSymbol(sym: Symbol): Boolean = {
    (!sym.isMethod || !forbiddenMethods(sym.decodedName)) &&
    (sym.ownersIterator
      .drop(1)
      .exists(
        _.isMethod
      )) // this also works for worksheets, since they are wrapped in `method main`

  }
  def prepareRename(
  ): Option[l.Range] = {
    soughtSymbols.flatMap { case (symbols, pos) =>
      if (symbols.forall(canRenameSymbol)) Some(pos.toLsp)
      else None
    }
  }

  val newName: String = name
    .map(name => Identifier.backtickWrap(name.stripBackticks))
    .getOrElse("newName")
  def collect(tree: Tree, toAdjust: Position): l.TextEdit = {
    val (pos, stripBackticks) = adjust(toAdjust)
    new l.TextEdit(
      pos.toLsp,
      if (stripBackticks) newName.stripBackticks else newName
    )
  }

  def rename(): List[l.TextEdit] = {
    val symbols = soughtSymbols.map(_._1).getOrElse(Set.empty)
    if (symbols.nonEmpty && symbols.forall(canRenameSymbol(_)))
      result()
    else Nil
  }

}
