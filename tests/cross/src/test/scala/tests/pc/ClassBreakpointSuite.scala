package tests.pc

import java.nio.file.Paths

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.CompilerVirtualFileParams

import munit.TestOptions
import org.eclipse.lsp4j.Position
import tests.BasePCSuite

class ClassBreakpointSuite extends BasePCSuite {

  check(
    "simple",
    """|package a
       |object B{
       |  class C
       |>>  println(0)
       |}
       |""".stripMargin,
    "a.B$"
  )

  check(
    "apply",
    """|package a
       |object Bar {
       |  def apply(): Boolean = {
       |>>  true
       |  }
       |}
       |""".stripMargin,
    "a.Bar$"
  )

  check(
    "nested",
    """|package a
       |object Bar {
       |  class Foo{
       |    def apply(): Boolean = {
       |>>    true
       |    }
       |  }
       |}
       |""".stripMargin,
    "a.Bar$Foo"
  )

  check(
    "nested-object",
    """|package a
       |object Bar {
       |  object Foo{
       |    def apply(): Boolean = {
       |>>    true
       |    }
       |  }
       |}
       |""".stripMargin,
    "a.Bar$Foo$"
  )

  check(
    "no-package",
    """|
       |class B{
       |  class C
       |>>  println(0)
       |}
       |""".stripMargin,
    "B"
  )

  check(
    "method",
    """|package a.b
       |class B{
       |  class C
       |  def method() = {
       |    >>  println(0)
       |  }
       |}
       |""".stripMargin,
    "a.b.B"
  )

  check(
    "trait",
    """|package a.b
       |trait B{
       |  class C
       |  def method() = {
       |    >>  println(0)
       |  }
       |}
       |""".stripMargin,
    "a.b.B"
  )

  check(
    "package-object",
    """|package a.b
       |package object c{
       |  def method() = {
       |    >>  println(0)
       |  }
       |}
       |""".stripMargin,
    "a.b.c.package$"
  )

  def check(
      name: TestOptions,
      original: String,
      expected: String
  ): Unit =
    test(name) {
      implicit val ec: ExecutionContext = ExecutionContext.global
      val filename: String = "Main.scala"
      val line =
        original.split('\n').indexWhere(_.contains(">>"))

      val code = original.replace(">>", "")
      val vFile = new CompilerVirtualFileParams(
        Paths.get(filename).toUri(),
        original.replace(">>", "")
      )
      val pos = new Position(line, 0)
      presentationCompiler.enclosingClass(pos, vFile).toScala.map { result =>
        assert(result.isPresent())
        assertNoDiff(result.get(), expected)
      }
    }
}
