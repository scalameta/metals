package scala.meta.internal.pc

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.Signatures.Signature
import dotty.tools.dotc.util.SourcePosition

object MetalsSignatures:

  def signatures(
      path: List[tpd.Tree],
      pos: SourcePosition,
  )(using ctx: Context): (Int, Int, List[(Signature, Denotation)]) =
    val (paramN, callableN, alternatives) =
      Signatures.signatureHelp(path, pos.span)
    val infos = alternatives.flatMap { signature =>
      signature.denot.map {
        (signature, _)
      }
    }

    (paramN, callableN, infos)
  end signatures
end MetalsSignatures
