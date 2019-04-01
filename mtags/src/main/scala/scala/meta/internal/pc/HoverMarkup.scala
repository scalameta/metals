package scala.meta.internal.pc

object HoverMarkup {

  /**
   * Render the textDocument/hover result into markdown.
   *
   * @param expressionType The type of the expression over the cursor, for example "List[Int]".
   * @param symbolSignature The signature of the symbol over the cursor, for example
   *                        "def map[B](fn: A => B): Option[B]"
   * @param docstring The Scaladoc/Javadoc string for the symbol.
   */
  def apply(
      expressionType: String,
      symbolSignature: String,
      docstring: String
  ): String = {
    val markdown = new StringBuilder()
    val needsExpressionType = !symbolSignature.endsWith(expressionType)
    if (needsExpressionType) {
      markdown
        .append("**Expression type**:\n")
        .append("```scala\n")
        .append(expressionType)
        .append("\n```\n")
    }
    if (symbolSignature.nonEmpty) {
      markdown
        .append(if (needsExpressionType) "**Symbol signature**:\n" else "")
        .append("```scala\n")
        .append(symbolSignature)
        .append("\n```")
    }
    if (docstring.nonEmpty)
      markdown
        .append("\n")
        .append(docstring)
    markdown.toString()
  }

}
