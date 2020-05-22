package tests

import scala.collection.mutable

import scala.meta.internal.ansi.LineListener

import munit.Location

class LineListenerSuite extends BaseSuite {
  def check(
      name: String,
      act: LineListener => Unit,
      expected: List[String]
  )(implicit loc: Location): Unit = {
    test(name) {
      var buf = mutable.ListBuffer.empty[String]
      val output = new LineListener(line => buf += line)
      act(output)
      output.flushIfNonEmpty()
      assertDiffEqual(buf.toList, expected)
    }
  }

  check(
    "flush",
    { out =>
      out.appendString("a")
      out.appendString("b")
    },
    List("ab")
  )

  check(
    "loop",
    { out => out.appendString("a\nb") },
    List("a", "b")
  )

  check(
    "eol",
    { out => out.appendString("a\n") },
    List("a")
  )

  check(
    "eol2",
    { out => out.appendString("a\n\n") },
    List("a", "")
  )

  check(
    "ansi",
    { out => out.appendString(fansi.Color.Blue("blue").toString()) },
    List("blue")
  )

  check(
    "windows",
    { out => out.appendString("a\r\nb") },
    List("a", "b")
  )

  check(
    "emoji",
    { out => out.appendString("🇮🇸🇵🇱") },
    List("🇮🇸🇵🇱")
  )
}
