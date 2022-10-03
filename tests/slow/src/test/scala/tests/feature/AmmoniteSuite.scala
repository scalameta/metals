package tests.feature

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

class Ammonite213Suite extends tests.BaseAmmoniteSuite(V.ammonite213) {
  ivyCompletionsTest(
    version = V.ammonite213,
    artefactExpectedCompletionList = """
                                       |circe-refined
                                       |circe-refined_native0.4
                                       |circe-refined_sjs0.6
                                       |circe-refined_sjs1""".stripMargin,
  )
}

class Ammonite3Suite extends tests.BaseAmmoniteSuite(V.ammonite3) {
  ivyCompletionsTest(
    version = V.ammonite3,
    artefactExpectedCompletionList = """
                                       |circe-refined
                                       |circe-refined_native0.4
                                       |circe-refined_sjs1""".stripMargin,
  )
}

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
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)
    } yield {
      assertEmpty(client.workspaceErrorShowMessages)
    }
  }
}
