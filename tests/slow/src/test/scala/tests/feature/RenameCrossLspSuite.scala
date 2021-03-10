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
    scalaVersion = Some(V.scala3)
  )

}
