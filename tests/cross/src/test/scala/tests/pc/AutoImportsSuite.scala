package tests.pc

import tests.BaseCodeActionSuite
import scala.meta.pc.AutoImportsResult
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.TextEdits

object AutoImportsSuite extends BaseCodeActionSuite {

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

  def check(
      name: String,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  ): Unit =
    test(name) {
      val imports = getAutoImports(original)
      val obtained = imports.map(_.packageName()).mkString("\n")
      assertNoDiff(obtained, getExpected(expected, compat))
    }

  def checkEdit(name: String, original: String, expected: String): Unit =
    test(name) {
      val imports = getAutoImports(original)
      if (imports.isEmpty) fail("obtained no imports")
      val edits = imports.head.edits().asScala.toList
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  def getAutoImports(
      original: String,
      filename: String = "A.scala"
  ): List[AutoImportsResult] = {
    val (code, symbol, offset) = params(original)
    val result = pc
      .autoImports(
        symbol,
        CompilerOffsetParams("file:/" + filename, code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
