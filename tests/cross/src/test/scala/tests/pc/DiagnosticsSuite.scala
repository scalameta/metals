package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
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

  def check(
      name: String,
      code: String,
      expected: String
  ): Unit =
    test(name) {
      val params = new VirtualFileParams {

        override def uri(): URI = URI.create("file:/Highlight.scala")

        override def text(): String = code

        override def token(): CancelToken = EmptyCancelToken

        override def shouldReturnDiagnostics(): Boolean = true
      }

      val diags = presentationCompiler
        .didChange(params)
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
