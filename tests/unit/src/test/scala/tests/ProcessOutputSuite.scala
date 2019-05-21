package tests

import scala.meta.internal.metals.ProcessOutput
import scala.collection.mutable

object ProcessOutputSuite extends BaseSuite {
  def check(
      name: String,
      fn: ProcessOutput => Unit,
      expected: List[String]
  ): Unit = {
    test(name) {
      var buf = mutable.ListBuffer.empty[String]
      val output = new ProcessOutput(line => buf += line)
      fn(output)
      output.onProcessExit()
      assertEquals(buf.toList, expected)
    }
  }

  check(
    "flush",
    _.onStringOutput("a")
      .onStringOutput("b"),
    List("ab")
  )

  check(
    "loop",
    _.onStringOutput("a\nb"),
    List("a", "b")
  )

  check(
    "eol",
    _.onStringOutput("a\n"),
    List("a")
  )

  check(
    "eol2",
    _.onStringOutput("a\n\n"),
    List("a", "")
  )

  check(
    "ansi",
    _.onStringOutput(fansi.Color.Blue("blue").toString()),
    List("blue")
  )

  check(
    "windows",
    _.onStringOutput("a\r\nb"),
    List("a", "b")
  )
}
