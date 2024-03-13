package tests.feature

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction
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
        Nil,
      )
    } yield ()
  }

  checkEdit(
    "extension-import",
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
        |object IntEnrichment:
        |  extension (num: Int)
        |    def incr = num + 1
        |
        |/b/src/main/scala/x/B.scala
        |package x
        |def main =
        |  println(1.<<incr>>)
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("incr", "example.IntEnrichment")}
        |${ExtractValueCodeAction.title("1.incr")}
        |${ConvertToNamedArguments.title("println(...)")}
        |""".stripMargin,
    s"""|package x
        |
        |import example.IntEnrichment.incr
        |def main =
        |  println(1.incr)
        |""".stripMargin,
  )

  checkEdit(
    "toplevel-extension-import",
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
        |
        |extension (str: String)
        |  def identity = str
        |
        |extension (num: Int)
        |  def incr = num + 1
        |
        |/b/src/main/scala/x/B.scala
        |package x
        |def main =
        |  println(1.<<incr>>)
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("incr", "example.A$package")}
        |${ExtractValueCodeAction.title("1.incr")}
        |${ConvertToNamedArguments.title("println(...)")}
        |""".stripMargin,
    s"""|package x
        |
        |import example.incr
        |def main =
        |  println(1.incr)
        |""".stripMargin,
  )

  checkEdit(
    "6180",
    s"""|/metals.json
        |{
        |  "a":{"scalaVersion" : "${V.scala3}"},
        |  "b":{
        |    "scalaVersion" : "${V.scala3}",
        |    "dependsOn": ["a"]
        |  }
        |}
        |/a/src/main/scala/example/Foo.scala
        |package example
        |
        |trait Foo
        |
        |/b/src/main/scala/x/B.scala
        |package x
        |case class B(
        |) extends <<F>>oo
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Foo", "example")}
        |${CreateNewSymbol.title("Foo")}
        |""".stripMargin,
    s"""|package x
        |
        |import example.Foo
        |case class B(
        |) extends Foo
        |""".stripMargin,
  )
}
