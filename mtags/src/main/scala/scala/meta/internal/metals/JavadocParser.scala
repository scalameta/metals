package scala.meta.internal.metals

/**
 * Parses raw Javadoc comment strings, extracting body text and param tags.
 *
 * Stripping and tag-detection behavior is adopted from qdox's comment lexer
 * to maintain consistency with the previous qdox-based implementation.
 */
object JavadocParser {

  // Strips the comment delimiters and leading `*` from each line.
  // After removing `*`, exactly one space or tab is consumed (if present),
  // preserving any additional indentation.
  def stripComment(raw: String): List[String] = {
    val body = raw.stripPrefix("/**").stripSuffix("*/")
    body.linesIterator.toList.map { line =>
      val trimmed = line.replaceFirst("^\\s*", "")
      if (trimmed.startsWith("* "))
        trimmed.substring(2)
      else if (trimmed.startsWith("*\t"))
        trimmed.substring(2)
      else if (trimmed.startsWith("*"))
        trimmed.substring(1)
      else
        trimmed
    }
  }

  /**
   * Returns the body text of a Javadoc comment (before any `@tag` lines).
   * A line is considered a tag line only when it starts with `@` at the
   * beginning of the logical line (after stripping).
   */
  def extractBody(docComment: Option[String]): String = {
    docComment match {
      case None => ""
      case Some(raw) =>
        val lines = stripComment(raw)
        val bodyLines = lines.takeWhile(line => !line.startsWith("@"))
        bodyLines.mkString("\n").trim
    }
  }

  // Rewrites Javadoc `@param <T> desc` to Scaladoc `@tparam T desc` so the
  // ScaladocParser's HTML-cleaning step doesn't strip <T> as an unknown HTML
  // tag (which would leave the next word as the captured parameter name).
  private val JavadocTypeParamTag =
    """@param(\s+)<(\w+)>""".r

  /**
   * Rewrites Javadoc-only constructs into a Scaladoc-equivalent form so the
   * Scaladoc-based MarkdownGenerator can render Javadoc comments correctly.
   */
  def toScaladocCompatible(raw: String): String = {
    JavadocTypeParamTag.replaceAllIn(raw, m => s"@tparam${m.group(1)}${m.group(2)}")
  }

  /**
   * Extracts `@param` tags from a Javadoc comment, returning a map of
   * param name to `"name description"`. Supports multi-line tag descriptions:
   * continuation lines (that don't start with `@`) are appended to the
   * previous tag.
   */
  def extractParamTags(docComment: Option[String]): Map[String, String] = {
    docComment match {
      case None => Map.empty
      case Some(raw) =>
        val lines = stripComment(raw)
        val paramPattern = """@param\s+(\S+)(?:\s+(.*))?""".r

        val result = Map.newBuilder[String, String]
        var currentParam: String = null
        var currentDesc: StringBuilder = null
        var inParam = false

        def flushParam(): Unit = {
          if (inParam && currentParam != null) {
            val desc = currentDesc.toString.trim
            val value =
              if (desc.isEmpty) currentParam
              else s"$currentParam $desc"
            result += (currentParam -> value)
          }
        }

        for (line <- lines) {
          if (line.startsWith("@param ") || line.startsWith("@param\t")) {
            flushParam()
            line match {
              case paramPattern(name, desc) =>
                currentParam = name
                currentDesc = new StringBuilder
                if (desc != null) currentDesc.append(desc)
                inParam = true
              case _ =>
                inParam = false
            }
          } else if (line.startsWith("@")) {
            flushParam()
            inParam = false
            currentParam = null
          } else if (inParam) {
            // Continuation line for current param
            val trimmed = line.trim
            if (trimmed.nonEmpty) {
              if (currentDesc.nonEmpty) currentDesc.append(" ")
              currentDesc.append(trimmed)
            }
          }
        }

        flushParam()

        result.result()
    }
  }
}
