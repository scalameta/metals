package scala.meta.languageserver

import org.langmeta.inputs.Input

object Positions {
  def positionToOffset(
      input: Input.VirtualFile,
      line: Int,
      column: Int
  ): Int =
    positionToOffset(input.path, input.value, line, column)

  def positionToOffset(
      filename: String,
      contents: String,
      line: Int,
      column: Int
  ): Int = {
    def peek(idx: Int): Int =
      if (idx < contents.length) contents.charAt(idx) else -1
    var i, l = 0
    while (i < contents.length && l < line) {
      contents.charAt(i) match {
        case '\r' =>
          l += 1
          if (peek(i + 1) == '\n') i += 1

        case '\n' =>
          l += 1

        case _ =>
      }
      i += 1
    }

    if (l < line)
      throw new IllegalArgumentException(
        s"$filename: Can't find position $line:$column in contents of only $l lines long."
      )
    if (i + column < contents.length)
      i + column
    else
      throw new IllegalArgumentException(
        s"$filename: Invalid column. Position $line:$column in line '${contents.slice(i, contents.length).mkString}'"
      )
  }

}
