package scala.meta.languageserver.compiler

import scala.annotation.tailrec
import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.Position
import scala.reflect.internal.util.SourceFile
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
    findEnclosingCallSite(position).fold(empty) { callSite =>
      val lastParenPosition = position.withPoint(callSite.openParenOffset)
      val signatureInformations = for {
        member <- compiler
          .completionsAt(lastParenPosition)
          .matchingResults()
          .distinct
        if member.sym.isMethod
        if member.sym.asMethod.paramLists.headOption.exists { paramList =>
          paramList.length > callSite.activeArgument
        }
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
        activeParameter = Some(callSite.activeArgument)
      )
    }
  }
  case class CallSite(openParenOffset: Int, activeArgument: Int)
  private def findEnclosingCallSite(
      caret: Position
  ): Option[CallSite] = {
    @tailrec
    def loop(i: Int, openParens: Int, commas: Int): Option[CallSite] = {
      if (i < 0) None
      else {
        val char = caret.source.content(i)
        char match {
          case '(' =>
            if (openParens == 0) Some(CallSite(i, commas))
            else loop(i - 1, openParens - 1, commas)
          case ')' =>
            loop(i - 1, openParens + 1, commas)
          case ',' if openParens == 0 =>
            loop(i - 1, openParens, commas + 1)
          case _ =>
            loop(i - 1, openParens, commas)
        }
      }
    }
    loop(caret.point, 0, 0)
  }
}
