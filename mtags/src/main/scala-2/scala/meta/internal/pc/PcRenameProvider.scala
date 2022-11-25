package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

object PcRenameProvider {

  def prepareRename(
      compiler: MetalsGlobal,
      params: OffsetParams
  ): Option[Range] = {
    import compiler._

    def canRenameSymbol(sym: Symbol): Boolean = {
      pprint.log(sym.ownersIterator.map(s => (s, s.decodedName)).toList)
      val forbiddenMethods =
        Set("equals", "hashCode", "unapply", "unary_!", "!")
      (!sym.isMethod || !forbiddenMethods(
        sym.decodedName
      )) && (sym.ownersIterator
        .drop(1)
        .exists(
          _.isMethod
        )) // this also works for worksheets, since they are wrapped in `method main`

    }
    val unit: RichCompilationUnit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )
    val pos: Position = unit.position(params.offset)
    typeCheck(unit)
    val typedTree: Tree = locateTree(pos) match {
      // Check actual object if apply is synthetic
      case sel @ Select(qual, name)
          if name == nme.apply && qual.pos == sel.pos =>
        qual
      case Import(expr, _) if expr.pos.includes(pos) =>
        // imports seem to be marked as transparent
        locateTree(pos, expr, acceptTransparent = true)
      case t => t
    }
    val s = typedTree match {
      case (id: Ident) =>
        Some((id.symbol, id.symbol.pos))
      /* named argument, which is a bit complex:
       * foo(nam@@e = "123")
       */

      /* all definitions:
       * def fo@@o = ???
       * class Fo@@o = ???
       * etc.
       */
      case (df: DefTree) if df.namePos.includes(pos) =>
        Some((df.symbol, df.namePos))
      /* Import selectors:
       * import scala.util.Tr@@y
       */
      case (imp: Import) if imp.pos.includes(pos) =>
        imp.selector(pos).map(sym => (sym, sym.pos))
      /* simple selector:
       * object.val@@ue
       */
      case (sel: NameTree) if sel.namePos.includes(pos) =>
        Some((sel.symbol, sel.symbol.pos))

      // needed for classOf[AB@@C]`
      case lit @ Literal(Constant(TypeRef(_, sym, _)))
          if lit.pos.includes(pos) =>
        Some((sym, sym.pos))
      case _ =>
        None
    }
    s.collect {
      case (symbol, pos)
          if canRenameSymbol(symbol) && symbol.allOverriddenSymbols.forall(
            canRenameSymbol(_)
          ) =>
        pos.toLsp
    }
  }
}

class PcRenameProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams,
    name: Option[String]
) extends PcCollector[TextEdit](compiler, params) {
  import compiler._
  val newName: String = name.map(name => Identifier.backtickWrap(name.stripBackticks)).getOrElse("newName")
  def collect(tree: Tree, toAdjust: Position): TextEdit = {
    val (pos, stripBackticks) = adjust(toAdjust)
    new TextEdit(
      pos.toLsp,
      if (stripBackticks) newName.stripBackticks else newName
    )
  }
  def canRenameSymbol(sym: Symbol): Boolean = {
    val forbiddenMethods = Set("equals", "hashCode", "unapply", "unary_!", "!")
    (!sym.isMethod || !forbiddenMethods(sym.decodedName)) && sym.ownersIterator
      .drop(1)
      .exists(_.isMethod)

  }
  def rename(): List[TextEdit] = {

    val symbols = soughtSymbols.getOrElse(Set.empty)
    if (symbols.nonEmpty && symbols.forall(canRenameSymbol(_)))
      result()
    else Nil
  }

}
