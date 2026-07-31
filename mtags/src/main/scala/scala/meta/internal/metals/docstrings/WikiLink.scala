package scala.meta.internal.docstrings

/**
 * Shared parsing of scaladoc entity (wiki) links `[[ ... ]]`, so the renderer
 * and source-position extraction (go-to-definition) agree on a link's
 * boundaries and its target/title split — instead of separate regexes that
 * disagree on backticks, bracket nesting and parenthesised signatures
 * (scalameta/metals#3383).
 */
object WikiLink {

  /**
   * Splits a link's inner content into its target and optional title. The split
   * is the first run of whitespace that lies OUTSIDE a backtick-escaped name and
   * outside a parenthesised signature or type-argument list, so a backticked
   * target containing a space (`` `my type` ``), a member signature with spaces
   * (`foo(a: Int)`) and a type application (`foo[A, B](a: A)`) stay part of the
   * target rather than being cut off as a title (scalameta/metals#3383).
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
   * matching brackets, mirroring the renderer's grammar) enclose `offset`, so
   * source go-to-definition navigates exactly the links the renderer renders,
   * including the triple-bracket form the old regex truncated
   * (scalameta/metals#3383).
   */
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
            // The link occupies the half-open range `[i, end + open)`; an
            // inclusive upper bound would let the char right after the closing
            // brackets (e.g. the opening `[` of an adjacent `[[A]][[B]]`) resolve
            // to this link (scalameta/metals#3383).
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
