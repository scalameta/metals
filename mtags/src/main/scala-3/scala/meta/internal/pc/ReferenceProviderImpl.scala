package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.RangeParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

final class ReferenceProviderImpl(
    val driver: InteractiveDriver,
    val params: RangeParams,
) extends PcCollector[Either[Reference, Definition]](driver, params)
    with ReferenceProvider:

  val text = params.text.toCharArray()

  val range: l.Range = pos.toLsp

  override def collect(parent: Option[Tree])(
      tree: Tree,
      pos: SourcePosition,
  ): Either[Reference, Definition] =
    val (adjustedPos, _) = adjust(pos, forHighlight = true)
    tree match
      case v: ValDef =>
        Right(
          new Definition(
            v.sourcePos.toLsp,
            text.slice(v.rhs.sourcePos.start, v.rhs.sourcePos.end).mkString,
            (v.sourcePos.start, v.sourcePos.end),
            parent match
              case Some(_: Block) => true
              case _ => false,
          )
        )
      case _ =>
        Left(
          Reference(
            adjustedPos.toLsp,
            parent.map(p => (p.sourcePos.start, p.sourcePos.end)),
          )
        )
    end match
  end collect
end ReferenceProviderImpl
