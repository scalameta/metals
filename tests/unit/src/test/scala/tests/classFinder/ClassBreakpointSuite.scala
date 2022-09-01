package tests
package classFinder

import java.nio.file.Paths

import scala.meta.inputs.Input
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

import munit.TestOptions

class ClassBreakpointSuite extends BaseClassFinderSuite {

  check(
    "simple",
    """|package a
       |object B{
       |  class C
       |>>  println(0)
       |}
       |""".stripMargin,
    "a.B$",
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
    "a.Bar$",
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
    "a.Bar$Foo",
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
    "a.Bar$Foo$",
  )

  check(
    "no-package",
    """|
       |class B{
       |  class C
       |>>  println(0)
       |}
       |""".stripMargin,
    "B",
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
    "a.b.B",
  )

  check(
    "trait",
    """|package a
       |package b
       |trait B{
       |  class C
       |  def method() = {
       |    >>  println(0)
       |  }
       |}
       |""".stripMargin,
    "a.b.B",
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
    "a.b.c.package$",
  )

  check(
    "method-scala3",
    """|package a.b
       |def method() = {
       |>>  println(0)
       |}
       |""".stripMargin,
    "a.b.Main$package",
    scalaVersion = V.scala3,
  )

  check(
    "inner-class-scala3",
    """|package a
       |
       |@main 
       |def helloWorld(): Unit = {
       |  object Even {
       |>>  def unapply(s: String): Boolean = s.size % 2 == 0
       |  }
       |}
       |
       |""".stripMargin,
    "a.Main$package",
    scalaVersion = V.scala3,
  )

  check(
    "optional-braces",
    """|package a
       |
       |@main 
       |def hello(): Unit = 
       |  greet("Alice")
       |  greet("Bob")
       |  System.exit(0)
       |
       |def greet(name: String) = 
       |  val message = s"Hello, $name!"
       |>>println(message)
       |
       |""".stripMargin,
    "a.Main$package",
    scalaVersion = V.scala3,
  )

  def check(
      name: TestOptions,
      original: String,
      expected: String,
      scalaVersion: String = V.scala213,
  ): Unit =
    test(name) {
      val (buffers, classFinder) = init(scalaVersion)

      val filename: String = "Main.scala"
      val path = AbsolutePath(Paths.get(filename))
      val sourceText = original.replace(">>", "  ")
      val offset = original.indexOf(">>")
      val input = Input.VirtualFile(filename, sourceText)
      val pos: scala.meta.Position =
        scala.meta.Position.Range(input, offset, offset)
      buffers.put(path, sourceText)
      val sym = classFinder.findClass(path, pos.toLsp.getStart())
      assert(sym.isDefined)
      assertNoDiff(sym.get, expected)
    }
}
