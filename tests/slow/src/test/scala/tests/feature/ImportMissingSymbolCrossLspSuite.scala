package tests.feature

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.metals.{BuildInfo => V}

import tests.codeactions.BaseCodeActionLspSuite

class ImportMissingSymbolCrossLspSuite
    extends BaseCodeActionLspSuite("importMissingSymbol-cross") {

  test("scala3-import-from-deps") {
    val path = "b/src/main/scala/x/B.scala"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a":{"scalaVersion" : "${V.scala3}"},
            |  "b":{
            |    "scalaVersion" : "${V.scala3}",
            |    "dependsOn": ["a"]
            |  }
            |}
            |/a/src/main/scala/example/A.scala
            |package example
            |trait A
            |/$path
            |package x
            |class B(a: A)
            |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertCodeAction(
        path,
        s"""|package x
            |class B(a: <<A>>)
            |""".stripMargin,
        s"""|${ImportMissingSymbol.title("A", "example")}
            |${CreateNewSymbol.title("A")}""".stripMargin,
        Nil
      )
    } yield ()
  }
}
