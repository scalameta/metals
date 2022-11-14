package tests.pc

import java.nio.file.Path

import tests.BaseCompletionSuite
import tests.BaseExtractMethodSuite

class ExtractMethodNoIndentSuite extends BaseExtractMethodSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)
  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    List("-no-indent")

  checkEdit(
    "simple-expr",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  @@val a = <<123 + method(b)>>
        |}""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  def newMethod(): Int =
        |    123 + method(b)
        |
        |  val a = newMethod()
        |}""".stripMargin,
  )
  checkEdit(
    "multiple-expr",
    s"""|object A {
        |  @@val a = {
        |    val c = 1
        |    <<val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2>>
        |  }
        |}""".stripMargin,
    s"""|object A {
        |  def newMethod(c: Int): Int = {
        |    val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod(c)
        |  }
        |}""".stripMargin,
  )
}

class CompletionMatchNoIndentSuite extends BaseCompletionSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)
  override protected def scalacOptions(classpath: Seq[Path]): Seq[String] =
    List("-no-indent")

  checkEdit(
    "basic",
    s"""
       |object A {
       |  Option(1) match@@
       |}""".stripMargin,
    s"""
       |object A {
       |  Option(1) match {
       |\tcase$$0
       |}
       |}""".stripMargin,
    filter = !_.contains("exhaustive"),
  )

  checkEdit(
    "exhaustive",
    s"""
       |object A {
       |  Option(1) match@@
       |}""".stripMargin,
    s"""
       |object A {
       |  Option(1) match {
       |\tcase None => $$0
       |\tcase Some(value) =>
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
  )
}
