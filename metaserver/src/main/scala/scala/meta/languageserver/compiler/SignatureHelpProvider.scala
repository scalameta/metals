package scala.meta.languageserver.compiler

import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.Position
import langserver.types.ParameterInformation
import langserver.types.SignatureHelp
import langserver.types.SignatureInformation

object SignatureHelpProvider {
  def empty: SignatureHelp = SignatureHelp(Nil, None, None)
  def signatureHelp(compiler: Global, position: Position): SignatureHelp = {
    // Find the last leading open paren to get the method symbol
    // of this argument list. Note, this may still cause false positives
    // in cases like `foo(bar(), <cursor>)` since we will find the
    // symbol of `bar` when we want to match against `foo`.
    // Related https://github.com/scalameta/language-server/issues/52
    val lastParenOffset =
      position.source.content.lastIndexOf('(', position.point)
    if (lastParenOffset < 0) {
      // bail if we can't find open paren to prevent false positives.
      empty
    } else {
      val lastParenPosition = position.withPoint(lastParenOffset)
      val signatureInformations = for {
        member <- compiler
          .completionsAt(lastParenPosition)
          .matchingResults()
          .distinct
        if member.sym.isMethod
      } yield {
        val sym = member.sym.asMethod
        val parameterInfos = sym.paramLists.headOption.map { params =>
          params.map { param =>
            ParameterInformation(
              label = s"${param.nameString}: ${param.info.toLongString}",
              documentation = None
            )
          }
        }
        SignatureInformation(
          label = s"${sym.nameString}${sym.info.toLongString}",
          documentation = None,
          parameters = parameterInfos.getOrElse(Nil)
        )
      }
      SignatureHelp(
        signatures = signatureInformations,
        // TODO(olafur) populate activeSignature and activeParameter fields, see
        // https://github.com/scalameta/language-server/issues/52
        activeSignature = None,
        activeParameter = None
      )
    }
  }
}
