package tests.feature

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

class Ammonite213Suite extends tests.BaseAmmoniteSuite(V.ammonite213)

class Ammonite212Suite extends tests.BaseAmmoniteSuite(V.ammonite212) {

  test("global-version-fallback") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.scala212}"
           |  }
           |}
           |/main.sc
           |
           |val cantStandTheHeat = "stay off the street"
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
    } yield {
      assertEmpty(client.workspaceErrorShowMessages)
    }
  }
}
