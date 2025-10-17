package tests.pc

import java.net.URI

import scala.meta.internal.jsemanticdb.Semanticdb
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
}
