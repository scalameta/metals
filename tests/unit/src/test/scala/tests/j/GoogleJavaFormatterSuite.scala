package tests.j

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.GoogleJavaFormatter
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits
import scala.meta.{inputs => m}

import munit.Location
import tests.BaseSuite
import tests.TestingClient

class GoogleJavaFormatterSuite extends BaseSuite {
  private val client = new TestingClient(PathIO.workingDirectory, Buffers())
  private val formatter = new GoogleJavaFormatter(() => client)

  private val original =
    """|import java.util.Map;
       |import java.util.ArrayList;
       |
       |public class Example {
       |  void untouched( ){System.out.println("old");}
       |<<  void changed( ){System.out.println("new");}>>
       |}
       |""".stripMargin

  private val expected =
    """|import java.util.Map;
       |import java.util.ArrayList;
       |
       |public class Example {
       |  void untouched( ){System.out.println("old");}
       |
       |  void changed() {
       |    System.out.println("new");
       |  }
       |}
       |""".stripMargin

  checkRange("method-and-preserve-imports", original, expected)

  checkRange(
    "crlf",
    original.replace("\n", "\r\n"),
    expected.replace("\n", "\r\n"),
  )

  checkRange(
    "utf16-offsets",
    """|public class Example {
       |  String text = "中文😀"; <<void changed( ){System.out.println("new");}>>
       |}
       |""".stripMargin,
    """|public class Example {
       |  String text = "中文😀";
       |
       |  void changed() {
       |    System.out.println("new");
       |  }
       |}
       |""".stripMargin,
  )

  checkRange(
    "end-at-next-line-start",
    """|class Example {
       |<<  void changed( ){System.out.println("new");}
       |>>  void untouched( ){System.out.println("old");}
       |}
       |""".stripMargin,
    """|class Example {
       |  void changed() {
       |    System.out.println("new");
       |  }
       |
       |  void untouched( ){System.out.println("old");}
       |}
       |""".stripMargin,
  )

  checkRange(
    "end-of-file-without-newline",
    """|public class Example {
       |<<  void changed( ){System.out.println("new");}>>
       |}""".stripMargin,
    """|public class Example {
       |  void changed() {
       |    System.out.println("new");
       |  }
       |}""".stripMargin,
  )

  test("whole-document-range-preserves-imports") {
    val code = "import java.util.Map;\n\nclass Example {}\n"
    val input = m.Input.VirtualFile("Example.java", code)
    val edits = formatter.formatRange(
      input,
      m.Position.Range(input, 0, code.length),
    )
    assertNoDiff(TextEdits.applyEdits(code, edits), code)
  }

  test("already-formatted-range") {
    val code = "class Example {\n  int value = 1;\n}\n"
    val input = m.Input.VirtualFile("Example.java", code)
    val start = code.indexOf("  int")
    val end = code.indexOf(';') + 1
    assertEquals(
      formatter.formatRange(input, m.Position.Range(input, start, end)),
      Nil,
    )
  }

  test("multiple-ranges-use-original-offsets") {
    val code =
      """|class Example {
         |  void first( ){System.out.println("first");}
         |  void untouched( ){System.out.println("old");}
         |  void last( ){System.out.println("last");}
         |}
         |""".stripMargin
    val input = m.Input.VirtualFile("Example.java", code)
    val edits = for {
      method <- List("first", "last")
      start = code.indexOf(s"  void $method")
      end = code.indexOf('\n', start)
      edit <- formatter.formatRange(input, m.Position.Range(input, start, end))
    } yield edit
    assertNoDiff(
      TextEdits.applyEdits(code, edits),
      """|class Example {
         |  void first() {
         |    System.out.println("first");
         |  }
         |
         |  void untouched( ){System.out.println("old");}
         |
         |  void last() {
         |    System.out.println("last");
         |  }
         |}
         |""".stripMargin,
    )
  }

  private def checkRange(name: String, query: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(name) {
      val start = query.indexOf("<<")
      val end = query.indexOf(">>") - 2
      val code = query.replace("<<", "").replace(">>", "")
      val input = m.Input.VirtualFile("Example.java", code)
      val edits =
        formatter.formatRange(input, m.Position.Range(input, start, end))
      assert(edits.nonEmpty)
      val positions = edits.map(_.getRange.toMeta(input).get)
      assert(positions.forall(p => p.start > 0 || p.end < code.length))
      assert(positions.zip(positions.drop(1)).forall { case (left, right) =>
        left.end <= right.start
      })
      assertEquals(TextEdits.applyEdits(code, edits), expected)
    }
  }
}
