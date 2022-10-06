package scala.meta.internal.mtags

// Sources taken with modifications from Ammonite:
// https://github.com/lihaoyi/Ammonite/blob/73a874173cd337f953a3edc9fb8cb96556638fdd/amm/util/src/main/scala/ammonite/util/Model.scala#L71-L121
// Original licence: MIT
// Original author: Li Haoyi
trait KeywordWrapper {

  private val blockCommentStart = "/*"
  private val lineCommentStart = "//"

  def keywords: Set[String]

  final def needsBacktick(s: String): Boolean = {
    val chunks = s.split("_", -1)
    def validOperator(c: Char) = {
      c.getType == Character.MATH_SYMBOL ||
      c.getType == Character.OTHER_SYMBOL ||
      "!#%&*+-/:<=>?@\\^|~".contains(c)
    }
    val validChunks = chunks.zipWithIndex.forall { case (chunk, index) =>
      chunk.forall(c => c.isLetter || c.isDigit || c == '$') ||
      (chunk.forall(validOperator) &&
        // operators can only come last
        index == chunks.length - 1 &&
        // but cannot be preceded by only a _
        !(chunks.lift(index - 1).contains("") && index - 1 == 0))
    }

    val firstLetterValid =
      s(0).isLetter ||
        s(0) == '_' ||
        s(0) == '$' ||
        validOperator(s(0))

    val valid =
      validChunks &&
        firstLetterValid &&
        !keywords.contains(s) &&
        !s.contains(blockCommentStart) &&
        !s.contains(lineCommentStart)

    !valid
  }

  final def backtickWrap(
      s: String,
      exclusions: Set[String] = Set.empty
  ): String = {
    if (exclusions.contains(s)) s
    else if (s.isEmpty) "``"
    else if (s(0) == '`' && s.last == '`') s
    else if (needsBacktick(s)) "" + ('`') + s + '`'
    else s
  }
}

object KeywordWrapper {

  val Scala2Keywords: Set[String] = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "extends",
    "false", "finally", "final", "finally", "forSome", "for", "if", "implicit",
    "import", "lazy", "match", "new", "null", "object", "override", "package",
    "private", "protected", "return", "sealed", "super", "this", "throw",
    "trait", "try", "true", "type", "val", "var", "while", "with", "yield", "_",
    "macro", ":", ";", "=>", "=", "<-", "<:", "<%", ">:", "#", "@", "\u21d2",
    "\u2190"
  )

  // List of Scala 3 keywords
  // https://dotty.epfl.ch/docs/internals/syntax.html#keywords
  val Scala3HardKeywords: Set[String] = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
    "extends", "false", "final", "finally", "for", "given", "if", "implicit",
    "import", "lazy", "match", "new", "null", "object", "override", "package",
    "private", "protected", "return", "sealed", "super", "then", "throw",
    "trait", "true", "try", "type", "val", "var", "while", "with", "yield", ":",
    "=", "<-", "=>", "<:", ":>", "#", "@", "=>>", "?=>"
  )

  val Scala3SoftKeywords: Set[String] =
    Set("as", "derives", "end", "extension", "infix", "inline", "opaque",
      "open", "throws", "transparent", "using", "|", "*", "+", "-")

  class Scala2 extends KeywordWrapper {
    val keywords: Set[String] = Scala2Keywords
  }
  object Scala2 extends Scala2

  class Scala3 extends KeywordWrapper {
    val keywords: Set[String] = Scala3HardKeywords ++ Scala3SoftKeywords
  }
  object Scala3 extends Scala3
}
