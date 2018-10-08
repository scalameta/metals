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
  def colored[T](thunk: => T): T = {
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
        throw new TestFailedException(message)
    }
  }
}
