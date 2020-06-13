package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseRangesSuite

class CrossReferenceSuite extends BaseRangesSuite("cross-reference-suite") {

  check(
    "references-dotty",
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
    scalaVersion = Some(V.scala3)
  )

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String]
  ): Future[Unit] = {
    server.assertReferences(filename, edit, expected, base)
  }

}
