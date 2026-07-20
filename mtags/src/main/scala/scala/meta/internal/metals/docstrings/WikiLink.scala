package scala.meta.internal.docstrings

/**
 * Shared parsing of scaladoc/javadoc entity (wiki) links `[[ ... ]]` so the
 * renderer, go-to-definition and resolver agree on a link's boundaries and its
 * target/title split (scalameta/metals#3383).
 */
object WikiLink {

  /**
   * Splits a link's inner content into target and optional title at the first
   * whitespace that lies outside a backtick-escaped name and outside a
   * parenthesised signature or type-argument list (scalameta/metals#3383).
   */
  def splitTargetTitle(content: String): (String, Option[String]) = {
    val n = content.length
    var i = 0
    while (i < n && content.charAt(i).isWhitespace) i += 1
    val start = i
    var inBacktick = false
    var depth = 0
    var split = -1
    while (i < n && split < 0) {
      content.charAt(i) match {
        case '`' => inBacktick = !inBacktick
        case '(' | '[' if !inBacktick => depth += 1
        case ')' | ']' if !inBacktick && depth > 0 => depth -= 1
        case c if !inBacktick && depth == 0 && c.isWhitespace => split = i
        case _ =>
      }
      i += 1
    }
    if (split < 0) (content.substring(start).trim, None)
    else {
      val title = content.substring(split).trim
      val titleOpt = if (title.isEmpty) None else Some(title)
      (content.substring(start, split), titleOpt)
    }
  }

  /**
   * The target of the entity link whose brackets (`[[ ... ]]`, or any `n >= 2`
   * matching brackets, mirroring the renderer) enclose `offset`, so source
   * go-to-definition navigates exactly the links the renderer makes clickable
   * (scalameta/metals#3383).
   */
  /**
   * The target of the Javadoc inline link (`{@link ... }` / `{@linkplain ... }`)
   * whose braces enclose `offset`, matching what the renderer extracts so source
   * clicks navigate the same links. The first token is the target; the rest is
   * the label (scalameta/metals#3383).
   */
  def javadocAtOffset(text: String, offset: Int): Option[String] = {
    val tags = List("{@linkplain", "{@link")
    val n = text.length
    var i = 0
    var result: Option[String] = None
    while (i < n && result.isEmpty) {
      if (text.charAt(i) == '{') {
        tags.find(text.startsWith(_, i)) match {
          case Some(tag) =>
            val end = text.indexOf('}', i + tag.length)
            if (end < 0) i += 1
            else {
              if (offset >= i && offset <= end) {
                val target =
                  splitTargetTitle(text.substring(i + tag.length, end))._1
                if (target.nonEmpty) result = Some(target)
              }
              i = end + 1
            }
          case None => i += 1
        }
      } else i += 1
    }
    result
  }

  /**
   * The symbol reference of a `@see` BLOCK tag whose target encloses `offset`.
   * `@see` isn't an inline `{@link}` / `[[ ... ]]`, so without this its reference
   * is clickable on hover yet dead from the source. Only the first token is the
   * target; a quoted string or HTML anchor is plain text, not a symbol
   * (scalameta/metals#3383).
   */
  def seeTagAtOffset(text: String, offset: Int): Option[String] = {
    val tag = "@see"
    val n = text.length
    var i = 0
    var result: Option[String] = None
    while (i < n && result.isEmpty) {
      val boundary = i + tag.length
      val isTag =
        text.startsWith(tag, i) &&
          isBlockTagStart(text, i) &&
          (boundary >= n || text.charAt(boundary).isWhitespace)
      if (isTag) {
        // A bare `@see`'s reference may sit on a continuation line, so skip
        // whitespace, newlines and the continuation `*` to reach it
        // (scalameta/metals#3383).
        var refStart = boundary
        var skipping = true
        while (refStart < n && skipping) {
          text.charAt(refStart) match {
            case ' ' | '\t' | '\n' | '\r' | '*' => refStart += 1
            case _ => skipping = false
          }
        }
        var lineEnd = refStart
        while (
          lineEnd < n &&
          text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r'
        ) lineEnd += 1
        val reference = text.substring(refStart, lineEnd)
        if (reference.nonEmpty) {
          val head = reference.charAt(0)
          // A quoted string or HTML anchor is plain text, a `/` starts the
          // comment's closing marker, and `@` starts the next block tag — none
          // is a `@see` reference (scalameta/metals#3383).
          if (head != '"' && head != '<' && head != '/' && head != '@') {
            val target = splitTargetTitle(reference)._1
            if (
              target.nonEmpty && offset >= refStart &&
              offset <= refStart + target.length
            ) result = Some(target)
          }
        }
        // A new block tag (`@`) ends this `@see`'s body, so rewind to it and let
        // it be scanned as its own tag (scalameta/metals#3383).
        i =
          if (refStart < n && text.charAt(refStart) == '@') refStart
          else lineEnd
      } else i += 1
    }
    result
  }

  /**
   * Whether `i` begins a BLOCK tag: everything back to the line start is only
   * comment scaffolding (whitespace, a `*`, or the opening marker), so a `@see`
   * in prose or a `{@link}` body isn't mistaken for one (scalameta/metals#3383).
   */
  private def isBlockTagStart(text: String, i: Int): Boolean = {
    var j = i - 1
    var ok = true
    var atLineStart = false
    while (j >= 0 && !atLineStart && ok) {
      text.charAt(j) match {
        case '\n' | '\r' => atLineStart = true
        case ' ' | '\t' | '*' | '/' => j -= 1
        case _ => ok = false
      }
    }
    ok
  }

  def atOffset(text: String, offset: Int): Option[String] = {
    val n = text.length
    var i = 0
    var result: Option[String] = None
    while (i < n && result.isEmpty) {
      if (text.charAt(i) == '[') {
        var open = 0
        while (i + open < n && text.charAt(i + open) == '[') open += 1
        if (open >= 2) {
          val contentStart = i + open
          val end = text.indexOf("]" * open, contentStart)
          if (end < 0) i += open
          else {
            // The link spans the half-open range `[i, end + open)`; an inclusive
            // upper bound would let the char right after the closing brackets
            // resolve to this link (scalameta/metals#3383).
            if (offset >= i && offset < end + open)
              result = Some(
                splitTargetTitle(text.substring(contentStart, end))._1
              )
            i = end + open
          }
        } else i += 1
      } else i += 1
    }
    result
  }
}
