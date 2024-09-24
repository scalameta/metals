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

  renamed(
    "ends-comma",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object main {
       |  def myMethod(
       |      s1: String,
       |      s2: String,
       |      s3: String,
       |      s4: String,
       |  ) = s1 ++ s2 ++ s3 ++ s4
       |
       |  val word1 = "hello"
       |  val <<word2>> = "world"
       |
       |  myMethod(
       |    word1,
       |    <<word2>>,
       |    s3 = word1,
       |    s4 = <<word2>>,
       |  )
       |}
       |""".stripMargin,
    newName = "NewSymbol",
    scalaVersion = Some(V.scala3),
  )

}
