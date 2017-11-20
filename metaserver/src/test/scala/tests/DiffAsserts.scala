package tests

object DiffAsserts {

  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  ): Boolean = {
    val result = compareContents(obtained, expected)
    if (result.isEmpty) true
    else {
      throw DiffFailure(title, expected, obtained, result)
    }
  }

  private def header[T](t: T): String = {
    val line = s"=" * (t.toString.length + 3)
    s"$line\n=> $t\n$line"
  }

  private case class DiffFailure(
      title: String,
      expected: String,
      obtained: String,
      diff: String
  ) extends Exception(
        title + "\n" + error2message(obtained, expected)
      )

  private def error2message(obtained: String, expected: String): String = {
    val sb = new StringBuilder
    if (obtained.length < 1000) {
      sb.append(
        s"""#${header("Obtained")}
            #${stripTrailingWhitespace(obtained)}
            #
            #""".stripMargin('#')
      )
    }
    sb.append(
      s"""#${header("Diff")}
          #${stripTrailingWhitespace(compareContents(obtained, expected))}"""
        .stripMargin('#')
    )
    sb.toString()
  }

  private def stripTrailingWhitespace(str: String): String =
    str.replaceAll(" \n", "∙\n")

  private def compareContents(original: String, revised: String): String =
    compareContents(original.trim.split("\n"), revised.trim.split("\n"))

  private def compareContents(
      original: Seq[String],
      revised: Seq[String]
  ): String = {
    import collection.JavaConverters._
    val diff = difflib.DiffUtils.diff(original.asJava, revised.asJava)
    if (diff.getDeltas.isEmpty) ""
    else
      difflib.DiffUtils
        .generateUnifiedDiff(
          "original",
          "revised",
          original.asJava,
          diff,
          1
        )
        .asScala
        .drop(3)
        .mkString("\n")
  }

}
