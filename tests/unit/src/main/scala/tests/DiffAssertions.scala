package tests

import org.scalactic.source.Position
import scala.util.control.NonFatal
import utest.ufansi.Color

/** Bridge for scalameta testkit DiffAssertions and utest assertions */
object DiffAssertions extends scala.meta.testkit.DiffAssertions {
  def expectNoDiff(obtained: String, expected: String, hint: String = "")(
      implicit pos: Position
  ): Unit = {
    colored {
      assertNoDiff(obtained, expected, hint)
    }
  }
  def colored[T](
      thunk: => T
  )(implicit filename: sourcecode.File, line: sourcecode.Line): T = {
    try {
      thunk
    } catch {
      case NonFatal(e) =>
        val message = e.getMessage.lines
          .map { line =>
            if (line.startsWith("+")) Color.Green(line)
            else if (line.startsWith("-")) Color.LightRed(line)
            else Color.Reset(line)
          }
          .mkString("\n")
        val location = s"failed assertion at ${filename.value}:${line.value}\n"
        throw new TestFailedException(location + message)
    }
  }
}
