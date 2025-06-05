package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken

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

  def check(
      name: String,
      code: String,
      expected: String
  ): Unit =
    test(name) {
      val params = CompilerVirtualFileParams(
        URI.create("file:/Highlight.scala"),
        code,
        EmptyCancelToken
      )

      val diags = presentationCompiler
        .diagnostics(params)
        .get
        .asScala
        .map { diag =>
          val start = diag.getRange().getStart()
          val end = diag.getRange().getEnd()
          val message = diag.getMessage()
          s"${start.getLine()}:${start.getCharacter()}-${end.getLine()}:${end.getCharacter()} - $message"
        }
        .toList

      assertNoDiff(diags.mkString("\n"), expected)
    }

}
