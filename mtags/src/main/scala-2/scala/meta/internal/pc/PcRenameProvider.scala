package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

class PcRenameProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams,
    name: Option[String]
) extends WithSymbolSearchCollector[l.TextEdit](compiler, params) {
  import compiler._
  private val forbiddenMethods =
    Set("equals", "hashCode", "unapply", "apply", "<init>", "unary_!", "!")

  private val soughtSymbolNames = soughtSymbols match {
    case Some((symbols, _)) =>
      symbols
        .filterNot(_.isErroneous)
        .map(symbol => symbol.decodedName.toString)
    case None => Set.empty[String]
  }

  def canRenameSymbol(sym: Symbol): Boolean = {
    val name = sym.decodedName.toString
    def sameName = soughtSymbolNames(name)
    def isLocal =
      sym.ownersIterator
        .drop(1)
        .exists(owner => owner.isMethod || owner.isAnonymousFunction)

    // this also works for worksheets, since they are wrapped in `method main`
    (!sym.isMethod || !forbiddenMethods(name)) && isLocal && sameName
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
  def collect(
      parent: Option[Tree]
  )(tree: Tree, toAdjust: Position, sym: Option[Symbol]): l.TextEdit = {
    val (pos, stripBackticks) = toAdjust.adjust(text, forRename = true)
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
