package tests.util

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Messages
import tests.BaseLspSuite

class WarningsLspSuite extends BaseLspSuite("warnings") {
  // NOTE(olafur) Ignored because at the time of this writing we have no deprecated
  // Scala versions.
  test("deprecated-scala".ignore) {
    cleanWorkspace()
    val using = V.deprecatedScalaVersions.head
    val recommended = ScalaVersions.recommendedVersion(using)
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
          Set(using),
          Set(recommended)
        )
      )
    } yield ()
  }
}
