package scala.meta.languageserver.compiler

import scala.annotation.tailrec
import scala.meta.languageserver.compiler.CompilerUtils._
import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import langserver.types.ParameterInformation
import langserver.types.SignatureHelp
import langserver.types.SignatureInformation

object SignatureHelpProvider {
  def empty: SignatureHelp = SignatureHelp(Nil, None, None)
  def signatureHelp(compiler: Global, position: Position): SignatureHelp = {
    findEnclosingCallSite(position).fold(empty) { callSite =>
      val lastParenPosition = position.withPoint(callSite.openParenOffset)
      // NOTE(olafur) this statement is intentionally before `completionsAt`
      // even if we don't use fallbackSymbol. typedTreeAt triggers compilation
      // of the code that prevents a StringIndexOutOfBounds in `completionsAt.
      val fallbackSymbol = compiler
        .typedTreeAt(position.withPoint(callSite.openParenOffset - 1))
        .symbol
      val completions =
        safeCompletionsAt[compiler.type](compiler, lastParenPosition)
      val matchedSymbols: Seq[compiler.Symbol] =
        if (completions.isEmpty) {
          if (fallbackSymbol != null) {
            // Can happen for synthetic .apply. This implementation does not
            // correctly return overloads for case classes or even overloads
            // defined in this compilation unit, despite the usage of `.alternatives`
            fallbackSymbol.alternatives
          } else Nil
        } else {
          completions.flatMap { completion =>
            if (completion.sym.isClass) {
              // Match primary and secondary constructors, without this customization
              // we would only return the primary constructor.
              completion.sym.info.members.filter { member =>
                member.name == compiler.nme.CONSTRUCTOR
              }
            } else {
              completion.sym :: Nil
            }
          }
        }
      val signatureInformations = for {
        symbol <- matchedSymbols
        if symbol.isMethod
        if symbol.asMethod.paramLists.headOption.exists { paramList =>
          paramList.length > callSite.activeArgument
        }
      } yield {
        val methodSymbol = symbol.asMethod
        val parameterInfos = methodSymbol.paramLists.headOption.map { params =>
          params.map { param =>
            ParameterInformation(
              label = s"${param.nameString}: ${param.info.toLongString}",
              documentation = None
            )
          }
        }
        SignatureInformation(
          label = s"${methodSymbol.nameString}${methodSymbol.info.toLongString}",
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
  private def findEnclosingCallSite(caret: Position): Option[CallSite] = {
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
