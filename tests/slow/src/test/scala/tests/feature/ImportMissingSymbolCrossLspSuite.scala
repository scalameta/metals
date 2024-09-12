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

  check(
    "scalac-explain-flag",
    """|package a
       |
       |object A {
       |  val f = <<Future>>.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
    scalaVersion = "3.3.3",
    scalacOptions = List("-explain"),
  )

  check(
    "i6475",
    """|package com.scalaFakePackage.test.latestCommentByItem.domain.projection.latestCommentByItemView
       |package subscriber {
       |  class CreateLatestCommentByItemSubscriber
       |}
       |package a {
       |  object O {
       |    val g: <<CreateLatestCommentByItemSubscriber>> = ???
       |  }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("CreateLatestCommentByItemSubscriber", "com.scalaFakePackage.test.latestCommentByItem.domain.projection.latestCommentByItemView.subscriber")}
        |${CreateNewSymbol.title("CreateLatestCommentByItemSubscriber")}""".stripMargin,
    """|package com.scalaFakePackage.test.latestCommentByItem.domain.projection.latestCommentByItemView
       |
       |import com.scalaFakePackage.test.latestCommentByItem.domain.projection.latestCommentByItemView.subscriber.CreateLatestCommentByItemSubscriber
       |package subscriber {
       |  class CreateLatestCommentByItemSubscriber
       |}
       |package a {
       |  object O {
       |    val g: CreateLatestCommentByItemSubscriber = ???
       |  }
       |}
       |""".stripMargin,
    scalaVersion = V.scala3,
  )

  check(
    "i6475-2",
    """|package com.randomMetalsTest.app.latestCommentByItem.domain.projection.latestCommentByItemView
       |package subscriber {
       |  class DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted
       |}
       |package a {
       |  object O {
       |    val g: <<DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted>> = ???
       |  }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title(
         "DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted",
         "com.randomMetalsTest.app.latestCommentByItem.domain.projection.latestCommentByItemView.subscriber",
       )}
        |${CreateNewSymbol.title("DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted")}
        |""".stripMargin,
    """|package com.randomMetalsTest.app.latestCommentByItem.domain.projection.latestCommentByItemView
       |
       |import com.randomMetalsTest.app.latestCommentByItem.domain.projection.latestCommentByItemView.subscriber.DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted
       |package subscriber {
       |  class DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted
       |}
       |package a {
       |  object O {
       |    val g: DeleteLatestCommentByItemViewOnLatestCommentByItemDeleted = ???
       |  }
       |}
       |""".stripMargin,
    scalaVersion = V.scala3,
  )

  check(
    "i6732-happy",
    s"""|package example.a {
        |  private [example] object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = <<A>>.foo
        |  }
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("A", "example.a")}
        |${CreateNewSymbol.title("A")}
        |""".stripMargin,
    s"""|import example.a.A
        |package example.a {
        |  private [example] object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = A.foo
        |  }
        |}
        |""".stripMargin,
    scalaVersion = V.scala3,
  )

  check(
    "i6732-negative (private)",
    s"""|package example.a {
        |  private object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = <<A>>
        |  }
        |}
        |""".stripMargin,
    "",
    s"""|package example.a {
        |  private object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = A
        |  }
        |}
        |""".stripMargin,
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == ImportMissingSymbol.title("A", "example.a"),
    scalaVersion = V.scala3,
  )

  check(
    "i6732-negative (private with out of path access boundary)",
    s"""|package exampleA.a {
        |  private [exampleA] object A {
        |    val foo = "foo"
        |  }
        |}
        |val bar = <<A>>
        |""".stripMargin,
    "",
    s"""|package exampleA.a {
        |  private [exampleA] object A {
        |    val foo = "foo"
        |  }
        |}
        |val bar = A
        |""".stripMargin,
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == ImportMissingSymbol.title("A", "example.a"),
    scalaVersion = V.scala3,
  )
}
