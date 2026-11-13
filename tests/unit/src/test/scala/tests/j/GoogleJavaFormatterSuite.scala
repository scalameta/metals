package tests.j

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.GoogleJavaFormatter
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.config.GoogleJavaFormatStyle
import scala.meta.inputs as m

import munit.Location
import tests.BaseSuite
import tests.TestingClient

class GoogleJavaFormatterSuite extends BaseSuite {
  private val client = new TestingClient(PathIO.workingDirectory, Buffers())
  private val formatter = new GoogleJavaFormatter(
    () => client,
    () => GoogleJavaFormatStyle.Google,
  )

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
    "aosp-method-and-preserve-imports",
    original,
    """|import java.util.Map;
       |import java.util.ArrayList;
       |
       |public class Example {
       |  void untouched( ){System.out.println("old");}
       |
       |    void changed() {
       |        System.out.println("new");
       |    }
       |}
       |""".stripMargin,
    GoogleJavaFormatStyle.Aosp,
  )

  test("aosp-whole-document-import-order") {
    val code =
      """|import java.util.List;
         |import com.example.Thing;
         |import android.view.View;
         |
         |class Example {
         |List<Thing> things;
         |View view;
         |}
         |""".stripMargin
    val formatter = new GoogleJavaFormatter(
      () => client,
      () => GoogleJavaFormatStyle.Aosp,
    )
    val input = m.Input.VirtualFile("Example.java", code)
    assertNoDiff(
      TextEdits.applyEdits(code, formatter.format(input)),
      """|import android.view.View;
         |
         |import com.example.Thing;
         |
         |import java.util.List;
         |
         |class Example {
         |    List<Thing> things;
         |    View view;
         |}
         |""".stripMargin,
    )
  }

  test("style-is-read-on-each-request") {
    var style: GoogleJavaFormatStyle = GoogleJavaFormatStyle.Google
    var reads = 0
    val formatter = new GoogleJavaFormatter(
      () => client,
      () => {
        reads += 1
        style
      },
    )
    val code = "class Example {\nint value;\n}\n"
    val input = m.Input.VirtualFile("Example.java", code)
    val range = m.Position.Range(input, 0, code.length)
    val google = "class Example {\n  int value;\n}\n"
    val aosp = "class Example {\n    int value;\n}\n"
    assertNoDiff(TextEdits.applyEdits(code, formatter.format(input)), google)
    assertEquals(reads, 1)
    style = GoogleJavaFormatStyle.Aosp
    assertNoDiff(TextEdits.applyEdits(code, formatter.format(input)), aosp)
    assertEquals(reads, 2)
    assertNoDiff(
      TextEdits.applyEdits(code, formatter.formatRange(input, range)),
      aosp,
    )
    assertEquals(reads, 3)
    style = GoogleJavaFormatStyle.Google
    assertNoDiff(
      TextEdits.applyEdits(code, formatter.formatRange(input, range)),
      google,
    )
    assertEquals(reads, 4)
  }

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

  private def checkRange(
      name: String,
      query: String,
      expected: String,
      style: GoogleJavaFormatStyle = GoogleJavaFormatStyle.Google,
  )(implicit
      loc: Location
  ): Unit = {
    test(name) {
      val start = query.indexOf("<<")
      val end = query.indexOf(">>") - 2
      val code = query.replace("<<", "").replace(">>", "")
      val input = m.Input.VirtualFile("Example.java", code)
      val formatter = new GoogleJavaFormatter(() => client, () => style)
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
