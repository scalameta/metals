package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams

import tests.BasePCSuite

class DiagnosticsSuite extends BasePCSuite {

  check(
    "basic",
    """|package example
       |
       |object Hello {
       |  def m = Foo.bar
       |  def abc = List(1,2,3).map { x => Bar(x) }
       |}
       |""".stripMargin,
    """|3:10-3:13 - not found: value Foo
       |4:35-4:38 - not found: value Bar
       |""".stripMargin
  )

  test("built-twice") {
    val uri = URI.create("file:/Twice.scala")
    val code =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    assertNoDiff(diagnostics(uri, code), StringNotInt)
    assertNoDiff(diagnostics(uri, code), StringNotInt)
  }

  test("built-twice-worksheet") {
    val uri = URI.create("file:/Twice.worksheet.sc")
    val code = "val a: Int = \"\""
    assertNoDiff(diagnostics(uri, code), ScriptStringNotInt)
    assertNoDiff(diagnostics(uri, code), ScriptStringNotInt)
  }

  test("after-hover") {
    val uri = URI.create("file:/Hover.scala")
    val code =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    presentationCompiler
      .hover(CompilerOffsetParams(uri, code, code.indexOf("a:")))
      .get()
    assertNoDiff(diagnostics(uri, code), StringNotInt)
  }

  test("after-hover-worksheet") {
    val uri = URI.create("file:/Hover.worksheet.sc")
    val code = "val a: Int = \"\""
    presentationCompiler
      .hover(CompilerOffsetParams(uri, code, code.indexOf("a:")))
      .get()
    assertNoDiff(diagnostics(uri, code), ScriptStringNotInt)
  }

  test("after-document") {
    val uri = URI.create("file:/Document.scala")
    val code =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    presentationCompiler.semanticdbTextDocument(uri, code).get()
    assertNoDiff(diagnostics(uri, code), StringNotInt)
  }

  test("after-edit") {
    val uri = URI.create("file:/Edit.scala")
    val before =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    val after =
      """|object O {
         |  val a: String = 1
         |}""".stripMargin
    assertNoDiff(diagnostics(uri, before), StringNotInt)
    assertNoDiff(
      diagnostics(uri, after),
      """|1:18-1:19 - type mismatch;
         | found   : Int(1)
         | required: String""".stripMargin
    )
  }

  private val StringNotInt =
    """|1:15-1:17 - type mismatch;
       | found   : String("")
       | required: Int""".stripMargin

  private val ScriptStringNotInt =
    """|0:13-0:15 - type mismatch;
       | found   : String("")
       | required: Int""".stripMargin

  def check(
      name: String,
      code: String,
      expected: String
  ): Unit =
    test(name) {
      assertNoDiff(
        diagnostics(URI.create("file:/Highlight.scala"), code),
        expected
      )
    }

  private def diagnostics(fileUri: URI, code: String): String = {
    val params = new VirtualFileParams {

      override def uri(): URI = fileUri

      override def text(): String = code

      override def token(): CancelToken = EmptyCancelToken

      // if should return diagnostics
      override def shouldReturnDiagnostics(): Boolean = true
    }

    presentationCompiler
      .didChange(params)
      .get
      .asScala
      .map { diag =>
        val start = diag.getRange().getStart()
        val end = diag.getRange().getEnd()
        val message = diag.getMessage().getLeft()
        s"${start.getLine()}:${start.getCharacter()}-${end.getLine()}:${end.getCharacter()} - $message"
      }
      .mkString("\n")
  }

}
