package scala.meta.internal.pc

import scala.meta.pc.RangeParams
import org.eclipse.{lsp4j => l}

final class ReferenceProviderImpl(
    override val compiler: MetalsGlobal,
    val params: RangeParams
) extends PcCollector[Either[Reference, Definition]](compiler, params)
    with ReferenceProvider {

  import compiler._

  val range: l.Range = new l.Range(
    pos.toLsp.getStart(),
    unit.position(params.endOffset()).toLsp.getEnd()
  )

  override def collect(parent: Option[Tree])(
      tree: Tree,
      pos: Position
  ): Either[Reference, Definition] = {
    val (adjustedPos, _) = adjust(pos, forHighlight = true)
    tree match {
      case v @ ValDef(_, _, _, rhs)
          if (!v.symbol.isParameter && !v.symbol.isMutable && !v.rhs.isEmpty) =>
        Right(
          new Definition(
            v.pos.toLsp,
            params.text().slice(rhs.pos.start, rhs.pos.end),
            (v.pos.start, v.pos.end),
            v.symbol.isLocalToBlock
          )
        )
      case _ =>
        Left(
          Reference(
            adjustedPos.toLsp,
            parent.map(p => (p.pos.start, p.pos.end))
          )
        )
    }
  }

}
