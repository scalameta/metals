package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.SemanticdbPrinter

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
    """|   package a
       |//         ^ definition a/
       |
       |   object O {
       |//        ^ definition a/O.
       |     val a = 123
       |//       ^ definition a/O.a.
       |     val b = a + 1
       |//       ^ definition a/O.b.
       |//           ^ reference a/O.a.
       |//             ^ reference scala/Int#`+`(+4).
       |   }
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
    """|   import $ivy.`org.kohsuke:github-api:1.114`
       |// ^ definition local0
       |
       |   object O {
       |//        ^ definition local1
       |     val a = 123
       |//       ^ definition local2
       |     val b = a + 1
       |//       ^ definition local3
       |//           ^ reference local2
       |//             ^ reference scala/Int#`+`(+4).
       |   }
       |""".stripMargin,
    filename = "A.worksheet.sc",
    compat = Map(
      "3" ->
        """|   import $ivy.`org.kohsuke:github-api:1.114`
           |
           |   object O {
           |//        ^ definition _empty_.O.
           |     val a = 123
           |//       ^ definition _empty_.O.a.
           |     val b = a + 1
           |//       ^ definition _empty_.O.b.
           |//           ^ reference _empty_.O.a.
           |//             ^ reference scala/Int#`+`(+4).
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
    assertNoDiff(diagnostics(uri, code), StringNotInt)
  }

  test("diagnostics-built-twice-worksheet") {
    val uri = new URI("file:///Twice.worksheet.sc")
    val code = "val a: Int = \"\""
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
    assertNoDiff(diagnostics(uri, code), StringNotInt)
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

  check(
    "worksheet-toplevel",
    """|val a = 123
       |val b = a + 1""".stripMargin,
    """|   val a = 123
       |   val b = a + 1
       |""".stripMargin,
    filename = "B.worksheet.sc"
  )

  check(
    "script-toplevel",
    """|val x = 123
       |val y = x + 1""".stripMargin,
    """|   val x = 123
       |// ^ definition local0
       |//     ^ definition local1
       |   val y = x + 1//     ^ definition local2
       |//         ^ reference local1
       |//           ^ reference scala/Int#`+`(+4).
       |""".stripMargin,
    filename = "B.sc"
  )

  check(
    "worksheet-magic-import-toplevel",
    """|import $dep.`com.lihaoyi::os-lib:0.11.4`
       |val a = 123
       |val b = a + 1""".stripMargin,
    """|   import $dep.`com.lihaoyi::os-lib:0.11.4`
       |   val a = 123
       |   val b = a + 1
       |""".stripMargin,
    filename = "D.worksheet.sc"
  )

  test("script-diagnostics") {
    val uri = new URI("file:///C.sc")
    assertNoDiff(diagnostics(uri, "val a: Int = \"\""), StringNotInt)
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

      val document = Semanticdb.TextDocument
        .parseFrom(doc.get())
        .toBuilder()
        .setText(original)
        .build()
      val obtained = SemanticdbPrinter.printDocument(document)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
    }
  }

  protected val StringNotInt: String =
    """|ERROR: type mismatch;
       | found   : String("")
       | required: Int""".stripMargin

  protected def diagnostics(uri: URI, code: String): String = {
    val bytes = presentationCompiler.semanticdbTextDocument(uri, code).get()
    Semanticdb.TextDocument
      .parseFrom(bytes)
      .getDiagnosticsList()
      .asScala
      .map(d => s"${d.getSeverity()}: ${d.getMessage()}")
      .mkString("\n")
  }
}
