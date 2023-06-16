package scala.meta.internal.pc.completions

import java.lang.StringBuilder

import scala.collection.immutable.Nil

import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.Identifier
import scala.meta.internal.pc.InterpolationSplice
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait InterpolatorCompletions { this: MetalsGlobal =>

  /**
   * A completion to select type members inside string interpolators.
   *
   * Example: {{{
   *   // before
   *   s"Hello $name.len@@!"
   *   // after
   *   s"Hello ${name.length()$0}"
   * }}}
   *
   * @param query the member query, "len" in the  example above.
   * @param ident the identifier from where we select a member from, "name" above.
   * @param literalPart the string literal part of the interpolator trailing
   *                    the identifier including cursor instrumentation, "len_CURSOR_!"
   *                    in the example above.
   * @param cursor the cursor position where the completion is triggered, `@@` in the example above.
   * @param text the text of the original source file without `_CURSOR_` instrumentation.
   */
  case class InterpolatorTypeCompletion(
      query: String,
      ident: Ident,
      literalPart: Literal,
      cursor: Position,
      text: String
  ) extends CompletionPosition {
    val pos: l.Range = ident.pos.withEnd(cursor.point).toLsp
    def newText(sym: Symbol): String = {
      new StringBuilder()
        .append('{')
        .append(text, ident.pos.start, ident.pos.end)
        .append('.')
        .append(Identifier.backtickWrap(sym.getterName.decoded))
        .append(sym.snippetCursor)
        .append('}')
        .toString
    }

    override def contribute: List[Member] = {
      metalsTypeMembers(ident.pos).collect {
        case m if CompletionFuzzy.matches(query, m.sym.name) =>
          val edit = new l.TextEdit(pos, newText(m.sym))
          // for VS Code we need to include the entire `ident.member`
          val filterText = ident.name.decoded + "." + m.sym.getterName.decoded
          new TextEditMember(filterText, edit, m.sym)
      }
    }
  }

  /**
   * A completion to convert a string literal into a string literal, example `"Hello $na@@"`.
   *
   * When converting a string literal into an interpolator we need to ensure a few cases:
   *
   * - escape existing `$` characters into `$$`, which are printed as `\$\$` in order to
   *   escape the TextMate snippet syntax.
   * - wrap completed name in curly braces `s"Hello ${name}_` when the trailing character
   *   can be treated as an identifier part.
   * - insert the  leading `s` interpolator.
   * - place the cursor at the end of the completed name using TextMate `$0` snippet syntax.
   *
   * @param lit The string literal, includes an instrumented `_CURSOR_` that we need to handle.
   * @param pos The offset position of the cursor, right below `@@_CURSOR_`.
   * @param interpolator Metadata about this interpolation, the location of the leading dollar
   *                     character and whether the completed name needs to be wrapped in
   *                     curly braces.
   * @param text The text of the original source code without the instrumented `_CURSOR_`.
   */
  case class InterpolatorScopeCompletion(
      lit: Literal,
      pos: Position,
      interpolator: InterpolationSplice,
      text: String
  ) extends CompletionPosition {

    val offset: Int =
      if (lit.pos.focusEnd.line == pos.line) CURSOR.length else 0
    val nameStart: Position =
      pos.withStart(pos.start - interpolator.name.size)
    val nameRange = nameStart.toLsp
    val hasClosingBrace: Boolean = text.charAt(pos.point) == '}'
    val hasOpeningBrace: Boolean = text.charAt(
      pos.start - interpolator.name.size - 1
    ) == '{'

    def additionalEdits(): List[l.TextEdit] = {
      val interpolatorEdit =
        if (text.charAt(lit.pos.start - 1) != 's')
          List(new l.TextEdit(lit.pos.withEnd(lit.pos.start).toLsp, "s"))
        else Nil
      val dollarEdits = for {
        i <- lit.pos.start to (lit.pos.end - CURSOR.length())
        if text.charAt(i) == '$' && i != interpolator.dollar
      } yield new l.TextEdit(pos.source.position(i).withEnd(i).toLsp, "$")
      interpolatorEdit ++ dollarEdits
    }

    def newText(sym: Symbol, identifier: String): String = {
      val out = new StringBuilder()
      val symbolNeedsBraces =
        interpolator.needsBraces ||
          identifier.startsWith("`") ||
          sym.isNonNullaryMethod ||
          // if we need to add prefix
          identifier.contains(".")
      if (symbolNeedsBraces && !hasOpeningBrace) {
        out.append('{')
      }
      out.append(identifier)
      out.append(sym.snippetCursor)
      if (symbolNeedsBraces && !hasClosingBrace) {
        out.append('}')
      }
      out.toString
    }

    override def contribute: List[Member] = {
      (workspaceSymbolListMembers(interpolator.name, pos) ++
        metalsScopeMembers(pos)).collect {
        case s: ScopeMember
            if CompletionFuzzy.matches(interpolator.name, s.sym.name) =>
          val filterText = s.sym.getterName.decoded
          s match {
            case _: WorkspaceMember =>
              new WorkspaceInterpolationMember(
                s.sym,
                additionalEdits(),
                newText(s.sym, _),
                Some(nameRange)
              )
            case _ =>
              val symbolName = s.sym.getterName.decoded
              val identifier = Identifier.backtickWrap(symbolName)
              val edit = new l.TextEdit(nameRange, newText(s.sym, identifier))
              new TextEditMember(
                filterText,
                edit,
                s.sym,
                additionalTextEdits = additionalEdits()
              )
          }
      }
    }
  }

  def isPossibleInterpolatorMember(
      lit: Literal,
      parent: Tree,
      text: String,
      cursor: Position
  ): Option[InterpolatorTypeCompletion] = {
    for {
      query <- interpolatorMemberSelect(lit)
      if text.charAt(lit.pos.point - 1) != '}'
      arg <- interpolatorMemberArg(parent, lit)
    } yield {
      InterpolatorTypeCompletion(
        query,
        arg,
        lit,
        cursor,
        text
      )
    }
  }

  private def interpolatorMemberArg(parent: Tree, lit: Literal): Option[Ident] =
    parent match {
      case Apply(
            Select(
              Apply(Ident(TermName("StringContext")), _ :: parts),
              _
            ),
            args
          ) =>
        parts.zip(args).collectFirst { case (`lit`, i: Ident) =>
          i
        }
      case _ =>
        None
    }

  private def interpolatorMemberSelect(lit: Literal): Option[String] =
    lit match {
      case Literal(Constant(s: String)) =>
        if (s.startsWith(s".$CURSOR")) Some("")
        else if (
          s.startsWith(".") &&
          s.length > 2 &&
          s.charAt(1).isUnicodeIdentifierStart
        ) {
          val cursor = s.indexOf(CURSOR)
          if (cursor < 0) None
          else {
            val isValidIdentifier =
              2.until(cursor).forall(i => s.charAt(i).isUnicodeIdentifierPart)
            if (isValidIdentifier) {
              Some(s.substring(1, cursor))
            } else {
              None
            }
          }
        } else {
          None
        }
      case _ =>
        None
    }

}
