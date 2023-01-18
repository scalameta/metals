package scala.meta.internal.pc

import scala.meta.internal.pc.Definition
import scala.meta.internal.pc.Reference
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class PcValReferenceProviderImpl(
    override val compiler: MetalsGlobal,
    val params: OffsetParams
) extends PcCollector[Occurrence](compiler, params)
    with PcValReferenceProvider {

  import compiler._

  val position: l.Position = pos.toLsp.getStart()

  override def collect(parent: Option[Tree])(
      tree: Tree,
      pos: Position
  ): Occurrence = {
    val (adjustedPos, _) = adjust(pos)
    tree match {
      case v @ ValDef(_, _, _, rhs)
          if (!v.symbol.isParameter && !v.symbol.isMutable && !v.rhs.isEmpty) =>
        new Definition(
          adjustedPos.toLsp,
          v.pos.toLsp,
          params.text().slice(rhs.pos.start, rhs.pos.end),
          RangeOffset(v.pos.start, v.pos.end),
          v.symbol.ownersIterator.drop(1).exists(_.isTerm)
        )
      case _ =>
        Reference(
          adjustedPos.toLsp,
          parent.map(p => RangeOffset(p.pos.start, p.pos.end))
        )
    }
  }

}
