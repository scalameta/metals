package scala.meta.internal.mtags

/**
 * Structured representation of a parsed Javadoc comment.
 */
case class JavadocComment(
    body: String,
    tags: List[JavadocTag]
) {
  def tagsByName(name: String): List[JavadocTag] =
    tags.filter(_.name == name)
}

case class JavadocTag(
    name: String,
    value: String
)

/**
 * Parses raw Javadoc comment text (including delimiters) into
 * a structured JavadocComment with body text and tags.
 */
object JavadocParser {

  // Parse a raw block comment string (Javadoc delimiters included).
  // Returns None if the comment is not a Javadoc comment.
  def parse(rawComment: String): Option[JavadocComment] = {
    if (rawComment == null || !rawComment.startsWith("/**"))
      return None

    val stripped = stripDelimiters(rawComment)
    val (body, tags) = splitBodyAndTags(stripped)
    Some(JavadocComment(body, tags))
  }

  // Strips Javadoc delimiters and leading asterisks from each line.
  private def stripDelimiters(raw: String): String = {
    val lines = raw.linesIterator.toList
    val cleaned = lines.map { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("/**"))
        trimmed.stripPrefix("/**").stripSuffix("*/").trim
      else if (trimmed.startsWith("*/"))
        ""
      else if (trimmed.startsWith("*"))
        trimmed.stripPrefix("*").stripPrefix(" ")
      else
        trimmed.stripSuffix("*/").trim
    }
    cleaned.mkString("\n").trim
  }

  // Splits the stripped Javadoc content into body text and tag list.
  // Tags start with @ at the beginning of a line.
  private def splitBodyAndTags(content: String): (String, List[JavadocTag]) = {
    val lines = content.split('\n').toList
    val bodyLines = List.newBuilder[String]
    val tagLines = List.newBuilder[(String, StringBuilder)]
    var inTags = false
    var currentTag: Option[(String, StringBuilder)] = None

    for (line <- lines) {
      val trimmed = line.trim
      if (trimmed.startsWith("@")) {
        inTags = true
        // flush current tag
        currentTag.foreach(tagLines += _)
        // parse new tag
        val spaceIdx = trimmed.indexOf(' ')
        if (spaceIdx > 0) {
          val name = trimmed.substring(1, spaceIdx)
          val value = trimmed.substring(spaceIdx + 1)
          currentTag = Some((name, new StringBuilder(value)))
        } else {
          val name = trimmed.substring(1)
          currentTag = Some((name, new StringBuilder))
        }
      } else if (inTags) {
        // continuation of current tag
        currentTag.foreach { case (_, sb) =>
          if (sb.nonEmpty) sb.append('\n')
          sb.append(line)
        }
      } else {
        bodyLines += line
      }
    }
    // flush last tag
    currentTag.foreach(tagLines += _)

    val body = bodyLines.result().mkString("\n").trim
    val tags = tagLines.result().map { case (name, sb) =>
      JavadocTag(name, sb.toString.trim)
    }
    (body, tags)
  }
}
