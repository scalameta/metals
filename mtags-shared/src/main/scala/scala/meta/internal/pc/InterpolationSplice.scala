package scala.meta.internal.pc

/**
 * Context used for providing completions inside strings.
 *
 * @param dollar offset position of $ for string interpolation, we're completing
 * @param name if we have s"$foo@@", name will be foo
 * @param needsBraces represents wether we have { after $ ?
 */
case class InterpolationSplice(dollar: Int, name: String, needsBraces: Boolean)

object InterpolationSplice {
  def apply(
      offset: Int,
      chars: Array[Char],
      originalText: String
  ): Option[InterpolationSplice] = {
    var i = offset
    while (
      i > 0 && (chars(i) match { case '$' | '\n' => false; case _ => true })
    ) {
      i -= 1
    }
    val isCandidate = i > 0 && i != offset &&
      chars(i) == '$' && {
        val start = chars(i + 1) match {
          case '{' => i + 2
          case _ => i + 1
        }
        start == offset || {
          chars(start).isUnicodeIdentifierStart &&
          (start + 1)
            .until(offset)
            .forall(j => chars(j).isUnicodeIdentifierPart)
        }
      }
    if (isCandidate) {
      val name = chars(i + 1) match {
        case '{' => originalText.substring(i + 2, offset)
        case _ => originalText.substring(i + 1, offset)
      }
      Some(
        InterpolationSplice(
          i,
          name,
          needsBraces = originalText.charAt(i + 1) == '{' ||
            (originalText.charAt(offset) match {
              case '"' => false // end of string literal
              case ch => ch.isUnicodeIdentifierPart
            })
        )
      )
    } else {
      None
    }
  }
}
