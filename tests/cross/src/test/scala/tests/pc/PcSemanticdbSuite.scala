package tests.pc

import java.net.URI

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
    """|import $ivy.`org.kohsuke:github-api:1.114`
       |
       |object O/*local0*/ {
       |  val a/*local1*/ = 123
       |  val b/*local2*/ = a/*local1*/ +/*scala.Int#`+`(+4).*/ 1
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
}
