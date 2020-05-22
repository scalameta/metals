package scala.meta.internal.pc.completions

import java.lang.StringBuilder

import scala.collection.immutable.Nil

import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.Identifier
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
    val pos: l.Range = ident.pos.withEnd(cursor.point).toLSP
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
    val filter: String =
      text.substring(ident.pos.start - 1, cursor.point - query.length)
    override def contribute: List[Member] = {
      metalsTypeMembers(ident.pos).collect {
        case m if CompletionFuzzy.matches(query, m.sym.name) =>
          val edit = new l.TextEdit(pos, newText(m.sym))
          val filterText = filter + m.sym.name.decoded
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
    val nameRange = nameStart.toLSP
    val hasClosingBrace: Boolean = text.charAt(pos.point) == '}'
    val hasOpeningBrace: Boolean = text.charAt(
      pos.start - interpolator.name.size - 1
    ) == '{'

    def additionalEdits(): List[l.TextEdit] = {
      val interpolatorEdit =
        if (text.charAt(lit.pos.start - 1) != 's')
          List(new l.TextEdit(lit.pos.withEnd(lit.pos.start).toLSP, "s"))
        else Nil
      val dollarEdits = for {
        i <- lit.pos.start to (lit.pos.end - CURSOR.length())
        if text.charAt(i) == '$' && i != interpolator.dollar
      } yield new l.TextEdit(pos.source.position(i).withEnd(i).toLSP, "$")
      interpolatorEdit ++ dollarEdits
    }

    def newText(sym: Symbol): String = {
      val out = new StringBuilder()
      val symbolName = sym.getterName.decoded
      val identifier = Identifier.backtickWrap(symbolName)
      val symbolNeedsBraces =
        interpolator.needsBraces ||
          identifier.startsWith("`") ||
          sym.isNonNullaryMethod
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

    val filter: String =
      text.substring(lit.pos.start, pos.point - interpolator.name.length)

    override def contribute: List[Member] = {
      metalsScopeMembers(pos).collect {
        case s: ScopeMember
            if CompletionFuzzy.matches(interpolator.name, s.sym.name) =>
          val edit = new l.TextEdit(nameRange, newText(s.sym))
          val filterText = filter + s.sym.name.decoded
          new TextEditMember(
            filterText,
            edit,
            s.sym,
            additionalTextEdits = additionalEdits()
          )
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

  case class InterpolationSplice(
      dollar: Int,
      name: String,
      needsBraces: Boolean
  )

  def isPossibleInterpolatorSplice(
      pos: Position,
      text: String
  ): Option[InterpolationSplice] = {
    val offset = pos.point
    val chars = pos.source.content
    var i = offset
    while (
      i > 0 && (chars(i) match { case '$' | '\n' => false; case _ => true })
    ) {
      i -= 1
    }
    val isCandidate = i > 0 &&
      chars(i) == '$' && {
      val start = chars(i + 1) match {
        case '{' => i + 2
        case _ => i + 1
      }
      start == offset || {
        chars(start).isUnicodeIdentifierStart &&
        (start + 1).until(offset).forall(j => chars(j).isUnicodeIdentifierPart)
      }
    }
    if (isCandidate) {
      val name = chars(i + 1) match {
        case '{' => text.substring(i + 2, offset)
        case _ => text.substring(i + 1, offset)
      }
      Some(
        InterpolationSplice(
          i,
          name,
          needsBraces = text.charAt(i + 1) == '{' ||
            (text.charAt(offset) match {
              case '"' => false // end of string literal
              case ch => ch.isUnicodeIdentifierPart
            })
        )
      )
    } else {
      None
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
        parts.zip(args).collectFirst {
          case (`lit`, i: Ident) => i
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
