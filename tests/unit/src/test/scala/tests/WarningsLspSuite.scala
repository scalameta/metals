package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.{BuildInfo => V}

class WarningsLspSuite extends BaseLspSuite("warnings") {

  test("unsupported-scala-212") {
    cleanWorkspace()
    val using = "2.12.4"
    for {
      _ <- initialize(
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
        Messages.UnsupportedScalaVersion.message(Set(using)),
      )
    } yield ()
  }

  test("no-warnings-scala-3") {
    cleanWorkspace()
    val using = V.scala3
    for {
      _ <- initialize(
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
