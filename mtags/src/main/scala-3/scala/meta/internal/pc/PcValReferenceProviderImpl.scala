package scala.meta.internal.pc

import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

final class PcValReferenceProviderImpl(
    val driver: InteractiveDriver,
    val params: OffsetParams,
) extends PcCollector[Occurrence](driver, params)
    with PcValReferenceProvider:

  val text = params.text.toCharArray()

  val position: l.Position = pos.toLsp.getStart()

  override def collect(parent: Option[Tree])(
      tree: Tree,
      pos: SourcePosition,
      sym: Option[Symbol],
  ): Occurrence =
    val (adjustedPos, _) = adjust(pos)
    tree match
      case v: ValDef =>
        new Definition(
          adjustedPos.toLsp,
          v.sourcePos.toLsp,
          text.slice(v.rhs.sourcePos.start, v.rhs.sourcePos.end).mkString,
          RangeOffset(v.sourcePos.start, v.sourcePos.end),
          v.symbol.ownersIterator
            .drop(1)
            .exists(e => e.isTerm),
        )
      case _ =>
        Reference(
          adjustedPos.toLsp,
          parent.map(p => RangeOffset(p.sourcePos.start, p.sourcePos.end)),
        )
    end match
  end collect

end PcValReferenceProviderImpl
