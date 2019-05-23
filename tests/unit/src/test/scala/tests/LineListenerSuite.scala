package tests

import scala.meta.internal.metals.LineListener
import scala.collection.mutable

object LineListenerSuite extends BaseSuite {
  def check(
      name: String,
      act: LineListener => Unit,
      expected: List[String]
  ): Unit = {
    test(name) {
      var buf = mutable.ListBuffer.empty[String]
      val output = new LineListener(line => buf += line)
      act(output)
      output.flush()
      assertEquals(buf.toList, expected)
    }
  }

  check(
    "flush", { out =>
      out.appendString("a")
      out.appendString("b")
    },
    List("ab")
  )

  check(
    "loop", { out =>
      out.appendString("a\nb"),
    },
    List("a", "b")
  )

  check(
    "eol", { out =>
      out.appendString("a\n")
    },
    List("a")
  )

  check(
    "eol2", { out =>
      out.appendString("a\n\n")
    },
    List("a", "")
  )

  check(
    "ansi", { out =>
      out.appendString(fansi.Color.Blue("blue").toString()),
    },
    List("blue")
  )

  check(
    "windows", { out =>
      out.appendString("a\r\nb")
    },
    List("a", "b")
  )
}
