package scala.meta.internal.jpc

/**
 * A line of code that contains an import, for example "import com.foo.bar"
 * or a wildcard import "import java.util.*". We assume all Java imports only
 * cover a single line, unlike Scala imports that can span multiple lines since
 * they support grouping (which is not supported in Java).
 */
case class ImportLine(
    line: String,
    lineNumber: Int
) {
  def isGreater(fqn: String): Boolean = {
    s"import $fqn".compareTo(line) < 0
  }
  // Returns the length of the longest shared prefix between the import line and the fqn.
  // For example, given "import com.foo.bar" and FQN "com.foo.Qux", the prefixMatchLength
  // is "com.foo.".length() = 9
  def importPrefixMatchLength(fqn: String): Int = {
    var length = 0
    def lineIndex = length + "import ".length
    while (
      lineIndex < line.length &&
      length < fqn.length &&
      line.charAt(lineIndex) == fqn.charAt(length)
    ) {
      length += 1
    }
    length
  }
}

object ImportLine {
  def fromText(text: String): Seq[ImportLine] = {
    for {
      (line, lineNumber) <- text.linesIterator.zipWithIndex
      if line.startsWith("import ")
    } yield new ImportLine(line, lineNumber)
  }.toSeq
}
