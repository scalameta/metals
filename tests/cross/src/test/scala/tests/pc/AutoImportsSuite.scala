package tests.pc

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
       |""".stripMargin
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
       |""".stripMargin
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
       |""".stripMargin
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
    selection = 1
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
       |import scala.collection.mutable.ListBuffer
       |
       |package object b {
       |  val l = s"${ListBuffer(2)}"
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
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
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
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkAmmoniteEdit(
    "first-auto-import-amm-script".tag(IgnoreScala3),
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

  checkAmmoniteEdit(
    "second-auto-import-amm-script".tag(IgnoreScala3),
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

}
