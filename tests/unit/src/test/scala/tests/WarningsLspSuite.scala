package tests

import scala.meta.internal.metals.BuildInfo as V
import scala.meta.internal.metals.Messages

class WarningsLspSuite extends BaseLspSuite("warnings") {

  test("unsupported-scala-212") {
    cleanWorkspace()
    val usedVersion = "2.12.4"
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${usedVersion}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.UnsupportedScalaVersion.message(Set(usedVersion)),
      )
    } yield ()
  }

  test("no-warnings-scala-3") {
    cleanWorkspace()
    val usedVersion = V.scala3
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${usedVersion}"
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
