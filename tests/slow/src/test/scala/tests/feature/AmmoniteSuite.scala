package tests.feature

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

class Ammonite213Suite extends tests.BaseAmmoniteSuite(V.ammonite213) {

  test("ivy-completion") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.ammonite213}"
           |  }
           |}
           |/main.sc
           |import $$ivy.`io.cir`
           |import $$ivy.`io.circe::circe-ref`
           |import $$ivy.`io.circe::circe-yaml:0.14`
           |""".stripMargin
      )
      _ <- server.didOpen("main.sc")
      _ <- server.didSave("main.sc")(identity)
      _ <- server.executeCommand(ServerCommands.StartAmmoniteBuildServer)

      groupExpectedCompletionList = "io.circe"
      groupCompletionList <- server.completion(
        "main.sc",
        "import $ivy.`io.cir@@`",
      )
      _ = assertNoDiff(groupCompletionList, groupExpectedCompletionList)

      artefactExpectedCompletionList =
        """
          |circe-refined
          |circe-refined_native0.4
          |circe-refined_sjs0.6
          |circe-refined_sjs1""".stripMargin
      artefactCompletionList <- server.completion(
        "main.sc",
        "import $ivy.`io.circe::circe-ref@@`",
      )
      _ = assertNoDiff(artefactCompletionList, artefactExpectedCompletionList)

      versionExpectedCompletionList = List("0.14.1", "0.14.0")
      response <- server.completionList(
        "main.sc",
        "import $ivy.`io.circe::circe-yaml:0.14@@`",
      )
      versionCompletionList = response
        .getItems()
        .asScala
        .map(_.getLabel())
        .toList
      _ = assertEquals(versionCompletionList, versionExpectedCompletionList)
    } yield ()
  }
}

class Ammonite3Suite extends tests.BaseAmmoniteSuite(V.ammonite3)

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
