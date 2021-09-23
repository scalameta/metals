package tests

import java.nio.file.Paths

import scala.meta.inputs.Input
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import munit.FunSuite
import munit.TestOptions

class ClassNameResolverSuite extends FunSuite {

  check(
    "simple1",
    """|package a
       |>>object B
       |class C
       |trait D
       |""".stripMargin,
    "a.B"
  )

  check(
    "simple2",
    """|package a
       |object B
       |>>class C
       |trait D
       |""".stripMargin,
    "a.C"
  )

  check(
    "simple3",
    """|package a
       |object B
       |class C
       |>>trait D
       |""".stripMargin,
    "a.D"
  )

  check(
    "no-package",
    """|object B
       |class C
       |>>trait D
       |""".stripMargin,
    "D"
  )

  check(
    "nested-package",
    """|package a.b
       |object B
       |>>class C
       |trait D
       |""".stripMargin,
    "a.b.C"
  )

  check(
    "multiline-package",
    """|package a
       |package b
       |object B
       |>>class C
       |trait D
       |""".stripMargin,
    "a.b.C"
  )

  check(
    "multiline-package-2",
    """|package a.b
       |package c.d
       |package e
       |object B
       |>>class C
       |trait D
       |""".stripMargin,
    "a.b.c.d.e.C"
  )

  check(
    "inner-class-1",
    """|package a
       |object Outer{
       |>>trait Inner
       |}
       |
       |""".stripMargin,
    "a.Outer"
  )

  check(
    "inner-class-2",
    """|package a
       |class Outer{
       |>>trait Inner
       |}
       |
       |""".stripMargin,
    "a.Outer"
  )

  check(
    "inner-class-3",
    """|package a
       |trait Outer{
       |>>trait Inner
       |}
       |
       |""".stripMargin,
    "a.Outer"
  )

  check(
    "nested-inner-class",
    """|package a
       |object Outer1 {
       |  object Outer2 {
       |>>  class Inner
       |  }
       |}
       |
       |""".stripMargin,
    "a.Outer1"
  )

  def check(
      name: TestOptions,
      original: String,
      expected: String,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala213
  ): Unit =
    test(name) {
      val buffers = Buffers()
      val buildTargets = new BuildTargets(_ => None)
      val selector = new ScalaVersionSelector(
        () => UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
        buildTargets
      )
      val trees = new Trees(buildTargets, buffers, selector)
      val classFinder = new ClassFinder(trees)
      val path = AbsolutePath(Paths.get(filename))
      val sourceText = original.replace(">>", "")
      val offset = original.indexOf(">>")
      val input = Input.VirtualFile(filename, sourceText)
      val pos: scala.meta.Position =
        scala.meta.Position.Range(input, offset, offset)
      buffers.put(path, sourceText)
      val tastyPath =
        classFinder.findTasty(path, pos.toLSP.getStart())

      assert(tastyPath.isDefined)
      assertNoDiff(tastyPath.get, expected)
    }
}
