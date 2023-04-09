package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.Signatures.Signature
import dotty.tools.dotc.util.SourcePosition

object MetalsSignatures:

  def signatures(
      path: List[tpd.Tree],
      pos: SourcePosition,
  )(using Context): (Int, Int, List[(Signature, Denotation)]) =
    val (paramN, callableN, alternatives) =
      Signatures.callInfo(path, pos.span)
    val infos = alternatives.flatMap { denot =>
      val updatedDenot =
        path.headOption
          .map { t =>
            val pre = t.qual
            denot.asSeenFrom(pre.tpe.widenTermRefExpr)
          }
          .getOrElse(denot)
      Signatures.toSignature(updatedDenot).map {
        (_, updatedDenot)
      }
    }
    (paramN, callableN, infos)
  end signatures
end MetalsSignatures
