package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseRenameLspSuite

class RenameCrossLspSuite extends BaseRenameLspSuite("rename-cross") {

  renamed(
    "scala3-outer",
    """|/a/src/main/scala/a/Main.scala
       |
       |@main def run() = {
       |  <<hello>>("Mark")
       |  <<hello>>("Anne")
       |}
       |def <<hel@@lo>>(name : String) : Unit = {
       |  println(s"Hello $name")
       |}
       |""".stripMargin,
    newName = "greeting",
    scalaVersion = Some(V.scala3),
  )

  renamed(
    "scala3-extension-params",
    """|/a/src/main/scala/a/Main.scala
       |
       |extension (<<sb@@d>>: String)
       |  def double = <<sbd>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
    newName = "greeting",
    scalaVersion = Some(V.scala3),
  )

  renamed(
    "scala3-extension-params-ref",
    """|/a/src/main/scala/a/Main.scala
       |
       |extension (<<sbd>>: String)
       |  def double = <<sb@@d>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
    newName = "greeting",
    scalaVersion = Some(V.scala3),
  )

  renamed(
    "variable-explicit2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  var <<v5>> = false
       |
       |  def f5: Boolean = {
       |    `<<v@@5>>_=`(true)
       |    <<v5>> == true
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
    scalaVersion = Some(V.scala3),
  )

}
