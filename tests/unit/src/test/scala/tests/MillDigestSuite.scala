package tests

import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.MillDigest

object MillDigestSuite extends BaseDigestSuite {

  override def digestCurrent(
      root: AbsolutePath
  ): Option[String] = MillDigest.current(root)

  checkSame(
    "solo-build.sc",
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin,
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin
  )

  checkSame(
    "multiline-comment",
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin,
    """
      |/build.sc
      |import mill._, scalalib._
      | /* This is a multi
      | line comment */
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin
  )

  checkSame(
    "comment",
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin,
    """
      |/build.sc
      |import mill._, scalalib._
      | // this is a comment
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin
  )

  checkSame(
    "whitespace",
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin,
    """
      |/build.sc
      |import mill._, scalalib._
      |
      |object foo extends ScalaModule {
      |
      | def scalaVersion =    "2.12.10"
      |}
    """.stripMargin
  )

  checkDiff(
    "significant-tokens",
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.10"
      |}
    """.stripMargin,
    """
      |/build.sc
      |import mill._, scalalib._
      |object foo extends ScalaModule {
      |  def scalaVersion = "2.12.7"
      |}
    """.stripMargin
  )

  def project(name: String) =
    s"""
       |import mill._, scalalib._
       |object $name extends ScalaModule {
       | def scalaVersion = "2.12.10"
       |}
      """.stripMargin

  checkDiff(
    "subproject",
    s"""
       |/build.sc
       |import $$file.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("other")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("renamed")}
    """.stripMargin
  )

  checkDiff(
    "subproject-^",
    s"""
       |/build.sc
       |import $$file.sub.^.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("other")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub.^.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("renamed")}
    """.stripMargin
  )

  checkDiff(
    "subproject-rename",
    s"""
       |/build.sc
       |import $$file.sub.{other => EasierName}
       |${project("foo")}
       |/sub/other.sc
       |${project("other")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub.{other => EasierName}
       |${project("foo")}
       |/sub/other.sc
       |${project("renamed")}
    """.stripMargin
  )

  checkDiff(
    "multiple-subprojects",
    s"""
       |/build.sc
       |import $$file.sub.other
       |import $$file.sub.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("bar")}
       |/sub/sub/other.sc
       |${project("man")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub.other
       |import $$file.sub.sub.other
       |${project("foo")}
       |/sub/other.sc
       |${project("bar")}
       |/sub/sub/other.sc
       |${project("tender")}
    """.stripMargin
  )

  checkDiff(
    "line-import",
    s"""
       |/build.sc
       |import $$file.sub.other, $$file.sub.sub.other
       |${project("foo")}
       |/sub/other.sc
       |import mill._, scalalib._
       |${project("bar")}
       |/sub/sub/other.sc
       |${project("man")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub.other, $$file.sub.sub.other
       |${project("foo")}
       |/sub/other.sc
       |import mill._, scalalib._
       |${project("bar")}
       |/sub/sub/other.sc
       |${project("tender")}
    """.stripMargin
  )

  checkDiff(
    "line-import-coma",
    s"""
       |/build.sc
       |import mill._, scalalib._
       |import $$file.sub1.{other, sub2.other}
       |${project("foo")}
       |/sub1/other.sc
       |${project("bar")}
       |/sub1/sub2/other.sc
       |${project("man")}
    """.stripMargin,
    s"""
       |/build.sc
       |import mill._, scalalib._
       |import $$file.sub1.{other, sub2.other}
       |${project("foo")}
       |/sub1/other.sc
       |${project("bar")}
       |/sub1/sub2/other.sc
       |${project("tender")}
    """.stripMargin
  )

  checkDiff(
    "line-import-no-prefix",
    s"""
       |/build.sc
       |import $$file.{other, sub2.other}
       |${project("foo")}
       |/other.sc
       |${project("bar")}
       |/sub2/other.sc
       |${project("man")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.{other, sub2.other}
       |${project("foo")}
       |/other.sc
       |${project("bar")}
       |/sub2/other.sc
       |${project("tender")}
    """.stripMargin
  )

  checkDiff(
    "line-import-coma-rename",
    s"""
       |/build.sc
       |import $$file.sub1.{other => betterName, sub2.other}
       |${project("foo")}
       |/sub1/other.sc
       |${project("bar")}
       |/sub1/sub2/other.sc
       |${project("man")}
    """.stripMargin,
    s"""
       |/build.sc
       |import $$file.sub1.{other => betterName, sub2.other}
       |${project("foo")}
       |/sub1/other.sc
       |${project("bar")}
       |/sub1/sub2/other.sc
       |${project("tender")}
    """.stripMargin
  )
}
