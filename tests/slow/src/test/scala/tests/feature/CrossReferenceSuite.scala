package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseRangesSuite

class CrossReferenceSuite extends BaseRangesSuite("cross-reference-suite") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(fallbackScalaVersion = Some(V.scala3))

  check(
    "references-scala3",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main{
       |  def <<hel@@lo>>() = println("Hello world")
       |  <<hello>>()
       |  <<hello>>()
       |  <<hello>>()
       |}
       |
       |""".stripMargin,
    scalaVersion = Some(V.scala3),
  )

  check(
    "references-scala3-standalone",
    """|/Main.scala
       |package a
       |
       |object Main{
       |  def <<hel@@lo>>() = println("Hello world")
       |  <<hello>>()
       |  <<hello>>()
       |  <<hello>>()
       |}
       |
       |""".stripMargin,
    scalaVersion = Some(V.scala3),
  )

  check(
    "import-rename",
    """|/Main.scala
       |package a
       |
       |import a.sample.{<<X1>> as X2}
       |
       |object sample:
       |  class <<X@@1>>
       |
       |def f: <<X2>> = new <<X2>>
       |""".stripMargin,
    scalaVersion = Some(V.scala3),
  )

  check(
    "import-rename2",
    """|/Main.scala
       |package a
       |
       |import sample.{<<X1>> as X@@2}
       |
       |object sample:
       |  class <<X1>>
       |
       |def f: <<X2>> = ???
       |""".stripMargin,
    scalaVersion = Some(V.scala3),
  )

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit] = {
    server.assertReferences(filename, edit, expected, base)
  }

}
