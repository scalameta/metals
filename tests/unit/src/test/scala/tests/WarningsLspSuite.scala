package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.{BuildInfo => V}

class WarningsLspSuite extends BaseLspSuite("warnings") {

  test("deprecated-scala-212") {
    cleanWorkspace()
    val using = V.deprecatedScalaVersions.filter(_.startsWith("2.12")).head
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.DeprecatedScalaVersion.message(
          Set(using)
        )
      )
    } yield ()
  }

  test("multiple-problems-scala") {
    cleanWorkspace()
    val using = V.deprecatedScalaVersions.filter(_.startsWith("2.12")).head
    val older = "2.12.4"
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  },
           |  "b": {
           |    "scalaVersion" : "$older"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        "Your build definition contains multiple unsupported and deprecated Scala versions."
      )
    } yield ()
  }

  test("deprecated-scala-211") {
    cleanWorkspace()
    val using = V.scala211
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.DeprecatedScalaVersion.message(Set(using))
      )
    } yield ()
  }

  test("unsupported-scala-212") {
    cleanWorkspace()
    val using = "2.12.4"
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.UnsupportedScalaVersion.message(Set(using))
      )
    } yield ()
  }

  test("deprecated-scala-213") {
    cleanWorkspace()
    val using = V.deprecatedScalaVersions.filter(_.startsWith("2.13")).head
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.DeprecatedScalaVersion.message(Set(using))
      )
    } yield ()
  }

  test("no-warnings-scala-3") {
    cleanWorkspace()
    val using = V.scala3
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${using}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertEmpty(client.workspaceMessageRequests)
    } yield ()
  }
}
