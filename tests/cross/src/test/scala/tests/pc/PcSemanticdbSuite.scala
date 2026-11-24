package tests.pc

import java.net.URI

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocument

import munit.Location
import tests.BasePCSuite

class PcSemanticdbSuite extends BasePCSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

  check(
    "simple",
    """|package a
       |
       |object O {
       |  val a = 123
       |  val b = a + 1
       |}""".stripMargin,
    """|package a
       |
       |object O/*a.O.*/ {
       |  val a/*a.O.a.*/ = 123
       |  val b/*a.O.b.*/ = a/*a.O.a.*/ +/*scala.Int#`+`(+4).*/ 1
       |}
       |""".stripMargin
  )

  check(
    "worksheet",
    """|import $ivy.`org.kohsuke:github-api:1.114`
       |
       |object O {
       |  val a = 123
       |  val b = a + 1
       |}""".stripMargin,
    // local0 comes most likely from the script object use to wrap ScriptSource
    """|/*local0*/import $ivy.`org.kohsuke:github-api:1.114`
       |
       |object O/*local1*/ {
       |  val a/*local2*/ = 123
       |  val b/*local3*/ = a/*local2*/ +/*scala.Int#`+`(+4).*/ 1
       |}
       |""".stripMargin,
    filename = "A.worksheet.sc",
    compat = Map(
      "3" ->
        """|import $ivy.`org.kohsuke:github-api:1.114`
           |
           |object O/*_empty_.O.*/ {
           |  val a/*_empty_.O.a.*/ = 123
           |  val b/*_empty_.O.b.*/ = a/*_empty_.O.a.*/ +/*scala.Int#`+`(+4).*/ 1
           |}
           |""".stripMargin
    )
  )

  test("diagnostics-built-twice") {
    val uri = new URI("file:///Twice.scala")
    val code =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    assertNoDiff(diagnostics(uri, code), StringNotInt)
    assertNoDiff(diagnostics(uri, code), "")
  }

  test("diagnostics-built-twice-worksheet") {
    val uri = new URI("file:///Twice.worksheet.sc")
    val code = "val a: Int = \"\""
    // the document lacks the diagnostics that the reporter has for a script
    assertNoDiff(diagnostics(uri, code), "")
    assertNoDiff(diagnostics(uri, code), "")
  }

  test("diagnostics-after-hover") {
    val uri = new URI("file:///Hover.scala")
    val code =
      """|object O {
         |  val a: Int = ""
         |}""".stripMargin
    presentationCompiler
      .hover(CompilerOffsetParams(uri, code, code.indexOf("a:")))
      .get()
    assertNoDiff(diagnostics(uri, code), "")
  }

  test("diagnostics-after-edit") {
    val uri = new URI("file:///Edit.scala")
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
      """|ERROR: type mismatch;
         | found   : Int(1)
         | required: String""".stripMargin
    )
  }

  def check(
      name: String,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
      filename: String = "A.scala"
  )(implicit loc: Location): Unit = {
    test(name) {
      val uri = new URI(s"file:///$filename")
      val doc = presentationCompiler.semanticdbTextDocument(uri, original)

      val document = TextDocument.parseFrom(doc.get())
      val withCode = document.withText(original)
      val obtained = Semanticdbs.printTextDocument(withCode)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
    }
  }

  private val StringNotInt =
    """|ERROR: type mismatch;
       | found   : String("")
       | required: Int""".stripMargin

  private def diagnostics(uri: URI, code: String): String = {
    val bytes = presentationCompiler.semanticdbTextDocument(uri, code).get()
    TextDocument
      .parseFrom(bytes)
      .diagnostics
      .map(d => s"${d.severity}: ${d.message}")
      .mkString("\n")
  }
}
