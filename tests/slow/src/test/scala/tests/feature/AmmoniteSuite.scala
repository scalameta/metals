package tests.feature

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import munit.IgnoreSuite

@IgnoreSuite
class Ammonite213Suite extends tests.BaseAmmoniteSuite(V.ammonite213) {

  test("ivy-completion-extended-initial-completion") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.scala213}"
           |  }
           |}
           |/main.sc
           |import $$ivy.org.scalame
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      groupCompletionList <- server.completion(
        "main.sc",
        "import $ivy.org.scalame@@",
      )
      _ = assertNoDiff(groupCompletionList, "org.scalameta")
    } yield ()
  }
}

@IgnoreSuite
class Ammonite3Suite extends tests.BaseAmmoniteSuite(V.ammonite3)

@IgnoreSuite
class Ammonite212Suite extends tests.BaseAmmoniteSuite(V.ammonite212) {

  test("global-version-fallback") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.scala213}"
           |  }
           |}
           |/main.sc
           |
           |val cantStandTheHeat = "stay off the street"
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
    } yield {
      assertEmpty(client.workspaceErrorShowMessages)
    }
  }
}
