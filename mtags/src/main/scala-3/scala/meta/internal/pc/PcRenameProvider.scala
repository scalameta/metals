package scala.meta.internal.pc
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}
import java.nio.file.Paths
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Flags.*

object PcRenameProvider:

  def canRenameSymbol(sym: Symbol)(using Context): Boolean =
    pprint.log(sym.ownersIterator.toList.map(s => (s, s.name.decoded)))
    val forbiddenMethods = Set("equals", "hashCode", "unapply", "unary_!", "!")
    (!sym.is(Method) || !forbiddenMethods(sym.decodedName))
    && sym.ownersIterator
      .drop(1)
      .exists(ow =>
        ow.is(Method) || (ow.is(ModuleClass)) && ow.name.decoded == "worksheet"
      )
    && sym.isDefinedInCurrentRun
  def prepareRename(
      driver: InteractiveDriver,
      params: OffsetParams,
  ): Option[l.Range] =
    val uri = params.uri()
    val filePath = Paths.get(uri)
    val sourceText = params.text
    val source =
      SourceFile.virtual(filePath.toString, sourceText)
    driver.run(uri, source)
    given ctx: Context = driver.currentCtx
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    val rawPath =
      Interactive
        .pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
        .dropWhile(t => // NamedArg anyway doesn't have symbol
          t.symbol == NoSymbol && !t.isInstanceOf[NamedArg] ||
            // same issue https://github.com/lampepfl/dotty/issues/15937 as below
            t.isInstanceOf[TypeTree]
        )
    val path = rawPath match
      case TypeApply(sel: Select, _) :: tail if sel.span.contains(pos.span) =>
        Interactive.pathTo(sel, pos.span) ::: rawPath
      case _ => rawPath
    val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)

    def findSymbol(path: List[Tree]): Option[(Symbol, SourcePosition)] =
      path match
        case (id: Ident) :: _ =>
          Some(id.symbol, id.sourcePos)
        case (sel: Select) :: _ if sel.nameSpan.contains(pos.span) =>
          Some(sel.symbol, sel.sourcePos)
        case (df: NamedDefTree) :: _ if df.nameSpan.contains(pos.span) =>
          Some(df.symbol, df.namePos)
        case (imp: Import) :: _ if imp.span.contains(pos.span) =>
          imp.selector(pos.span).map(sym => (sym, sym.sourcePos))
        case _ =>
          None
    val r = findSymbol(path).collect {
      case (symbol, pos)
          if canRenameSymbol(symbol) && symbol.allOverriddenSymbols.forall(
            canRenameSymbol(_)
          ) =>
        pos.toLsp
    }
    pprint.log(r)
    r
  end prepareRename
end PcRenameProvider

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
    pprint.log("wzsedlem")
    val symbols = soughtSymbols(path).getOrElse(Set.empty)
    pprint.log(symbols)
    if symbols.nonEmpty && symbols.forall(PcRenameProvider.canRenameSymbol(_))
    then

      val res = result()
      pprint.log(res)
      res
    else
      pprint.log("dupa")
      Nil
  end rename
end PcRenameProvider
