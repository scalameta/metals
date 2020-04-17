package tests.feature

import tests.BaseRenameLspSuite
import scala.meta.internal.metals.{BuildInfo => V}

class RenameCrossLspSuite extends BaseRenameLspSuite("rename-cross") {

  renamed(
    "dotty-outer",
    """|/a/src/main/scala/a/Main.scala
       |
       |@main def main() = {
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
