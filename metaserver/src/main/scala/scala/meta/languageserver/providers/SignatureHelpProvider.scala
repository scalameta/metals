package scala.meta.languageserver.providers

import scala.annotation.tailrec
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.compiler.CompilerEnrichments._
import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.SignatureHelpResult
import langserver.types.ParameterInformation
import langserver.types.SignatureInformation

object SignatureHelpProvider extends LazyLogging {
  def empty: SignatureHelpResult = SignatureHelpResult(Nil, None, None)
  def signatureHelp(compiler: Global, cursor: Cursor): SignatureHelpResult = {
    val unit = ScalacProvider.addCompilationUnit(
      global = compiler,
      code = cursor.contents,
      filename = cursor.uri,
      cursor = Some(cursor.offset + 1)
    )
    val position = unit.position(cursor.offset)
    findEnclosingMethod(position).fold(empty) { callSite =>
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
          paramList.length > callSite.activeArgument || {
            symbol.asMethod.isVarargs
          }
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
      val isVararg = signatureInformations.exists { info =>
        info.parameters.length < callSite.activeArgument
      }
      val activeArgument: Int =
        if (isVararg) signatureInformations.map(_.parameters.length - 1).max
        else callSite.activeArgument
      SignatureHelpResult(
        signatures = signatureInformations,
        // TODO(olafur) populate activeSignature and activeParameter fields, see
        // https://github.com/scalameta/language-server/issues/52
        activeSignature = None,
        activeParameter = Some(activeArgument)
      )
    }
  }
  case class CallSite(openParenOffset: Int, activeArgument: Int)
  private def findEnclosingMethod(caret: Position): Option[CallSite] = {
    val chars = caret.source.content
    val char = chars.lift
    val point =
      if (char(caret.point).contains(')') &&
        char(caret.point - 1).contains('(')) {
        // cursor is inside `()`
        caret.point - 1
      } else caret.point
    findOpen(chars, point, '(', ')').map {
      case c @ CallSite(openParen, activeArgument) =>
        if (!char(openParen - 1).contains(']')) c
        else {
          // Hop over the type parameter list `T` to find `Foo`: Foo[T](<<a>
          // NOTE(olafur) this is a pretty hacky approach to find the enclosing
          // method symbol of a type parameter. Ideally we could do something
          // like `Tree.parent`. I'm not yet familiar enough with the
          // presentation compiler to know if there exists such a utility.
          findOpen(chars, openParen - 2, '[', ']').fold(c) {
            case CallSite(openBracket, _) =>
              CallSite(openBracket - 1, activeArgument)
          }
        }
    }
  }
  private def findOpen(
      chars: Array[Char],
      start: Int,
      open: Char,
      close: Char
  ): Option[CallSite] = {
    @tailrec
    def loop(i: Int, opens: Int, commas: Int): Option[CallSite] = {
      if (i < 0) None
      else {
        val char = chars(i)
        if (char == open) {
          if (opens == 0) Some(CallSite(i, commas))
          else loop(i - 1, opens - 1, commas)
        } else if (char == close) {
          loop(i - 1, opens + 1, commas)
        } else if (char == ',' && opens == 0) {
          loop(i - 1, opens, commas + 1)
        } else {
          loop(i - 1, opens, commas)
        }
      }
    }
    loop(start, 0, 0)
  }
}
