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
      optSymbolSignature: Option[String],
      docstring: String,
      forceExpressionType: Boolean = false
  ): String = {
    val markdown = new StringBuilder()
    if (forceExpressionType || optSymbolSignature.isEmpty) {
      markdown
        .append(
          if (optSymbolSignature.isDefined) "**Expression type**:\n" else ""
        )
        .append("```scala\n")
        .append(expressionType)
        .append("\n```\n")
    }
    optSymbolSignature.foreach { symbolSignature =>
      if (symbolSignature.nonEmpty) {
        markdown
          .append(if (forceExpressionType) "**Symbol signature**:\n" else "")
          .append("```scala\n")
          .append(symbolSignature)
          .append("\n```")
      }
    }
    if (docstring.nonEmpty)
      markdown
        .append("\n")
        .append(docstring)
    markdown.toString()
  }

  def apply(body: String): String = {
    s"""|```scala
        |$body
        |```""".stripMargin
  }

}
