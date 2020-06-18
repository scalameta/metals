package tests.pc

import java.nio.file.Paths

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.AutoImportsResult

import munit.Location
import tests.BaseCodeActionSuite
import tests.BuildInfoVersions

class AutoImportsSuite extends BaseCodeActionSuite {

  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

  check(
    "basic",
    """|object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|scala.concurrent
       |java.util.concurrent
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|scala.concurrent
           |scala.concurrent.impl
           |java.util.concurrent
           |""".stripMargin
    )
  )

  checkEdit(
    "basic-edit",
    """|package a
       |
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "symbol-prefix-edit",
    """|package a
       |
       |object A {
       |  val l = new <<ArrayList>>[Int]
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.{util => ju}
       |
       |object A {
       |  val l = new ju.ArrayList[Int]
       |}
       |""".stripMargin
  )

  checkEdit(
    "interpolator-edit",
    """|package a
       |
       |object A {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "package-object",
    """|
       |package object metals{
       |  object ABC
       |}
       |object Main{
       | val obj = <<ABC>>
       |}
       |""".stripMargin,
    """|import metals.ABC
       |
       |package object metals{
       |  object ABC
       |}
       |object Main{
       | val obj = ABC
       |}
       |""".stripMargin
  )

  checkEdit(
    "import-inside-package-object",
    """|package a
       |
       |package object b {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |package object b {
       |  val l = s"${mutable.ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "multiple-packages",
    """|package a
       |package b
       |package c
       |
       |object A {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |package b
       |package c
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "multiple-packages-existing-imports",
    """|package a
       |package b
       |package c
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |package b
       |package c
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "first-auto-import-amm-script",
    "script.sc.scala",
    ammoniteWrapper(
      """val p: <<Path>> = ???
        |""".stripMargin
    ),
    // Import added *before* the wrapper here…
    // This *seems* wrong, but the logic converting the scala file
    // edits to sc file edits will simply add it at the beginning of
    // the sc file, as expected.
    "import java.nio.file.Path\n" +
      ammoniteWrapper(
        """val p: Path = ???
          |""".stripMargin
      )
  )

  checkEdit(
    "second-auto-import-amm-script",
    "script.sc.scala",
    ammoniteWrapper(
      """import java.nio.file.Files
        |val p: <<Path>> = ???
        |""".stripMargin
    ),
    ammoniteWrapper(
      """import java.nio.file.Files
        |import java.nio.file.Path
        |val p: Path = ???
        |""".stripMargin
    )
  )

  private def ammoniteWrapper(code: String): String =
    // Vaguely looks like a scala file that Ammonite generates
    // from a sc file.
    // Just not referencing any Ammonite class, that we don't pull
    // in the tests here.
    s"""|package ammonite
        |package $$file.`auto-import`
        |import _root_.scala.collection.mutable.{
        |  HashMap => MutableHashMap
        |}
        |
        |object test{
        |$code
        |}
        |""".stripMargin

  def check(
      name: String,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit =
    test(name) {
      val imports = getAutoImports(original, "A.scala")
      val obtained = imports.map(_.packageName()).mkString("\n")
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
    }

  def checkEdit(name: String, original: String, expected: String)(implicit
      loc: Location
  ): Unit =
    checkEdit(name, "A.scala", original, expected)

  def checkEdit(
      name: String,
      filename: String,
      original: String,
      expected: String
  )(implicit
      loc: Location
  ): Unit =
    test(name) {
      val imports = getAutoImports(original, filename)
      if (imports.isEmpty) fail("obtained no imports")
      val edits = imports.head.edits().asScala.toList
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  def getAutoImports(
      original: String,
      filename: String
  ): List[AutoImportsResult] = {
    val (code, symbol, offset) = params(original)
    val result = presentationCompiler
      .autoImports(
        symbol,
        CompilerOffsetParams(
          Paths.get(filename).toUri(),
          code,
          offset,
          cancelToken
        )
      )
      .get()
    result.asScala.toList
  }

}
