package tests.pc

import munit.Location
import tests.BaseAutoImportsSuite

class AutoImportsSuite extends BaseAutoImportsSuite {

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
    ),
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
       |""".stripMargin,
  )

  checkEdit(
    "basic-edit-comment",
    """|/**
       | * @param code
       | * @return
       |*/
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|import scala.concurrent.Future
       |/**
       | * @param code
       | * @return
       |*/
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "basic-edit-directive",
    """|// using scala 35
       |// using something else
       |
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|// using scala 35
       |// using something else
       |import scala.concurrent.Future
       |
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "scala-cli-sc-using-directives",
    """|object main {
       |/*<script>*///> using scala "3.1.3"
       |
       |object x {
       |  <<Try>>("1".toString)
       |}
       |}
       |
       |""".stripMargin,
    """|object main {
       |/*<script>*///> using scala "3.1.3"
       |import scala.util.Try
       |
       |object x {
       |  Try("1".toString)
       |}
       |}
       |""".stripMargin,
    filename = "A.sc",
  )

  checkEdit(
    "symbol-no-prefix",
    """|package a
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.UUID
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin,
  )

  checkEdit(
    "symbol-prefix-existing",
    """|package a
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.UUID
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin,
  )

  checkEdit(
    "symbol-prefix",
    """|package a
       |
       |object A {
       |  val l : <<Map>>[String, Int] = ???
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.{util => ju}
       |
       |object A {
       |  val l : ju.Map[String, Int] = ???
       |}
       |""".stripMargin,
  )

  checkEdit(
    "interpolator-edit-scala2".tag(IgnoreScala3),
    """|package a
       |
       |object A {
       |  val l = s"${<<Seq>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.Seq(2)}"
       |}
       |""".stripMargin,
    selection = 1,
  )

  checkEdit(
    "interpolator-edit-scala3".tag(IgnoreScala2),
    """|package a
       |
       |object A {
       |  val l = s"${<<Seq>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.Seq(2)}"
       |}
       |""".stripMargin,
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
       |""".stripMargin,
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
       |import scala.collection.mutable.ListBuffer
       |
       |package object b {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin,
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
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin,
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
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin,
  )

  checkAmmoniteEdit(
    "first-auto-import-amm-script",
    ammoniteWrapper(
      """|
         |val p: <<Path>> = ???
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|import java.nio.file.Path
         |
         |val p: Path = ???
         |""".stripMargin
    ),
  )

  checkAmmoniteEdit(
    "second-auto-import-amm-script",
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
    ),
  )

  checkAmmoniteEdit(
    "amm-objects",
    ammoniteWrapper(
      """|
         |object a {
         |  object b {
         |    val p: <<Path>> = ???
         |  }
         |}
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|import java.nio.file.Path
         |
         |object a {
         |  object b {
         |    val p: Path = ???
         |  }
         |}
         |""".stripMargin
    ),
  )

  checkAmmoniteEdit(
    "first-auto-import-amm-script-with-header",
    ammoniteWrapper(
      """|// scala 2.13.1
         |
         |val p: <<Path>> = ???
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|// scala 2.13.1
         |import java.nio.file.Path
         |
         |val p: Path = ???
         |""".stripMargin
    ),
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
        |/*<start>*/
        |$code
        |}
        |""".stripMargin

  // https://dotty.epfl.ch/docs/internals/syntax.html#soft-keywords
  List("infix", "inline", "opaque", "open", "transparent", "as", "derives",
    "end", "extension", "throws", "using").foreach(softKeywordCheck)

  private def softKeywordCheck(keyword: String)(implicit loc: Location) =
    checkEdit(
      s"'$keyword'-named-object".tag(IgnoreScala2),
      s"""|
          |object $keyword{ object ABC }
          |object Main{ val obj = <<ABC>> }
          |""".stripMargin,
      s"""|import $keyword.ABC
          |
          |object $keyword{ object ABC }
          |object Main{ val obj = ABC }
          |""".stripMargin,
    )

}
