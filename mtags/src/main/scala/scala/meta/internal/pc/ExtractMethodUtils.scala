package scala.meta.internal.pc

trait ExtractMethodUtils {
  def adjustIndent(
      line: String,
      newIndent: String,
      oldIndentLen: Int
  ): String = {
    var i = 0
    val additional = if (newIndent.indexOf("\t") != -1) "\t" else "  "
    while ((line(i) == ' ' || line(i) == '\t') && i < oldIndentLen) {
      i += 1
    }
    newIndent + additional + line.drop(i)
  }

  def genName(usedNames: Set[String], prefix: String): String = {
    if (!usedNames(prefix)) prefix
    else {
      var i = 0
      while (usedNames(s"$prefix$i")) {
        i += 1
      }
      s"$prefix$i"
    }
  }

  def asParams(
      noLongerAvailable: List[(String, String)],
      localRefs: Set[String]
  ): (String, String) = {
    val withType =
      noLongerAvailable.filter { case (key, _) =>
        localRefs.contains(key)
      }.sorted
    val methodParams = withType
      .map { case (name, tpe) => s"$name: $tpe" }
      .mkString(", ")
    val applParams = withType.map(_._1).mkString(", ")
    (methodParams, applParams)
  }

  def textToExtract(
      text: String,
      start: Int,
      end: Int,
      newIndent: String,
      oldIndentLen: Int
  ): String = {
    text
      .slice(start, end)
      .split("\n")
      .map(adjustIndent(_, newIndent, oldIndentLen))
      .mkString("\n")
  }
}
