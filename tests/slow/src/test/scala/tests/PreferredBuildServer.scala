package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

class PreferredBuildServer extends BaseLspSuite("preferred-build-server") {
  override def userConfig: UserConfiguration =
    super.userConfig.copy(defaultBspToBuildTool = true)

  test("start-sbt-when-preferred-no-bsp") {
    cleanWorkspace()

    val importMessage =
      Messages.GenerateBspAndConnect.params("sbt", "sbt").getMessage()

    client.showMessageRequestHandler = msg => {
      if (msg.getMessage() == importMessage)
        Some(Messages.GenerateBspAndConnect.notNow)
      else None
    }

    val fileLayout =
      s"""|/project/build.properties
          |sbt.version=${V.sbtVersion}
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |ThisBuild / scalaVersion := "${V.scala213}"
          |val a = project.in(file("a"))
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          | val a = 1
          |}
          |""".stripMargin
    FileLayout.fromString(fileLayout, workspace)

    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importMessage,
      )
      _ = assert(server.server.bspSession.isEmpty)
    } yield ()
  }

  test("start-sbt-when-preferred-with-bsp") {
    cleanWorkspace()

    val fileLayout =
      s"""|/project/build.properties
          |sbt.version=${V.sbtVersion}
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |ThisBuild / scalaVersion := "${V.scala213}"
          |val a = project.in(file("a"))
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          | val a = 1
          |}
          |""".stripMargin

    FileLayout.fromString(fileLayout, workspace)
    SbtServerInitializer.generateBspConfig(workspace, V.sbtVersion)

    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(
        server.server.tables.buildServers.selectedServer().get,
        "sbt",
      )
      _ = assert(server.server.bspSession.exists(_.main.isSbt))
    } yield ()
  }
}
