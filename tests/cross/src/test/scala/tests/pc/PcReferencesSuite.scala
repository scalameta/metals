package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.pc.PcReferencesRequest

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import tests.BasePCSuite
import tests.RangeReplace

class PcReferencesSuite extends BasePCSuite with RangeReplace {
  def check(
      name: TestOptions,
      original: String,
      compat: Map[String, String] = Map.empty
  )(implicit location: Location): Unit =
    test(name) {
      val edit = original.replaceAll("(<<|>>)", "")
      val expected = original.replaceAll("@@", "")
      val base = original.replaceAll("(<<|>>|@@)", "")

      val (code, offset) = params(edit, "Highlight.scala")
      val ranges = presentationCompiler
        .references(
          PcReferencesRequest(
            CompilerVirtualFileParams(
              URI.create("file:/Highlight.scala"),
              code,
              EmptyCancelToken
            ),
            includeDefinition = false,
            offsetOrSymbol = JEither.forLeft(offset)
          )
        )
        .get()
        .asScala
        .flatMap(_.locations().asScala.map(_.getRange()))
        .toList

      assertEquals(
        renderRangesAsString(base, ranges),
        getExpected(expected, compat, scalaVersion)
      )
    }

  check(
    "implicit-args",
    """|package example
       |
       |class Bar(i: Int)
       |
       |object Hello {
       |  def m(i: Int)(implicit b: Bar) = ???
       |  val foo = {
       |    implicit val b@@arr: Bar = new Bar(1)
       |    m<<>>(3)
       |  }
       |}
       |""".stripMargin,
    compat = Map("3" -> """|package example
                           |
                           |class Bar(i: Int)
                           |
                           |object Hello {
                           |  def m(i: Int)(implicit b: Bar) = ???
                           |  val foo = {
                           |    implicit val barr: Bar = new Bar(1)
                           |    m(3)<<>>
                           |  }
                           |}
                           |""".stripMargin)
  )

  check(
    "implicit-args-2",
    """|package example
       |
       |class Bar(i: Int)
       |class Foo(implicit b: Bar)
       |
       |object Hello {
       |  implicit val b@@arr: Bar = new Bar(1)
       |  val foo = <<>>new Foo
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|package example
                |
                |class Bar(i: Int)
                |class Foo(implicit b: Bar)
                |
                |object Hello {
                |  implicit val barr: Bar = new Bar(1)
                |  val foo = new Foo<<>>
                |}
                |""".stripMargin
    )
  )

  // for Scala 3 the symbol in bar reference is missing (<none>)
  check(
    "implicit-args-3".tag(IgnoreScala3),
    """|package example
       |
       |class Bar(i: Int)
       |class Foo(implicit b: Bar)
       |
       |object Hello {
       |  implicit val b@@arr = new Bar(1)
       |  for {
       |    _ <- Some(1)
       |    foo = <<>>new Foo()
       |  } yield ()
       |}
       |""".stripMargin
  )

  check(
    "case-class",
    """|case class Ma@@in(i: Int)
       |""".stripMargin
  )

  check(
    "case-class-with-implicit",
    """"|case class A()(implicit val fo@@o: Int)
       |""".stripMargin
  )

}
