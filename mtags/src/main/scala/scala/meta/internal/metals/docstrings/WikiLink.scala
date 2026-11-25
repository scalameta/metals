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
   * An entity link in a text. The link, brackets included, occupies the
   * half-open range `[start, end)`, and its target begins at `targetStart`.
   */
  final case class Link(start: Int, end: Int, target: String, targetStart: Int)

  /**
   * The entity links (`[[ ... ]]`, or any `n >= 2` matching brackets,
   * mirroring the renderer's grammar) in `text`, in source order.
   */
  def links(text: String): List[Link] = {
    val n = text.length
    val result = List.newBuilder[Link]
    var i = 0
    while (i < n) {
      if (text.charAt(i) == '[') {
        var open = 0
        while (i + open < n && text.charAt(i + open) == '[') open += 1
        if (open >= 2) {
          val contentStart = i + open
          val end = text.indexOf("]" * open, contentStart)
          if (end < 0) i += open
          else {
            val content = text.substring(contentStart, end)
            val target = splitTargetTitle(content)._1
            result += Link(
              i,
              end + open,
              target,
              contentStart + content.indexOf(target)
            )
            i = end + open
          }
        } else i += 1
      } else i += 1
    }
    result.result()
  }

  /**
   * The target of the entity link that encloses `offset`, so source
   * go-to-definition navigates exactly the links the renderer renders,
   * including the triple-bracket form the old regex truncated
   * (scalameta/metals#3383).
   */
  def atOffset(text: String, offset: Int): Option[String] =
    links(text).collectFirst {
      // The link range is half-open; an inclusive upper bound would let the
      // char right after the closing brackets (e.g. the opening `[` of an
      // adjacent `[[A]][[B]]`) resolve to this link (scalameta/metals#3383).
      case link if offset >= link.start && offset < link.end => link.target
    }
}
