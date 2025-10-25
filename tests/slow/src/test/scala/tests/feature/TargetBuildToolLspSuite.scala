package tests.feature

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite

class TargetBuildToolLspSuite extends BaseImportSuite("target-build-tool") {

  // SBT will be the main tool for this test
  def buildTool: SbtBuildTool = SbtBuildTool(None, workspace, () => userConfig)

  def alternativeBuildTool: MillBuildTool =
    MillBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = None

  test("target-build-tool-sbt") {
    // Override userConfig to prefer sbt
    val configuredUserConfig =
      UserConfiguration(targetBuildTool = Some("sbt"))
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala213}"
            |}
            |""".stripMargin,
        expectError = false,
        preInitialized = () => {
          server.server.tables.buildTool.reset()
          server.server.userConfigSync.updateUserConfiguration(configuredUserConfig)
        },
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Should NOT show chooseBuildToolMessage since config specifies sbt
          importBuildMessage,
        ).mkString("\n"),
      )
      _ <- server.server.indexingPromise.future
      // Verify that sbt was chosen
      _ = assert(server.server.tables.buildTool.selectedBuildTool().contains("sbt"))
    } yield ()
  }

  test("target-build-tool-mill") {
    // Override userConfig to prefer mill
    val configuredUserConfig =
      UserConfiguration(targetBuildTool = Some("mill"))
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala213}"
            |}
            |""".stripMargin,
        expectError = false,
        preInitialized = () => {
          server.server.tables.buildTool.reset()
          server.server.userConfigSync.updateUserConfiguration(configuredUserConfig)
        },
      )
      _ <- server.server.indexingPromise.future
      // Verify that mill was chosen
      _ = assert(server.server.tables.buildTool.selectedBuildTool().contains("mill"))
    } yield ()
  }

  test("target-build-tool-not-found") {
    // Set the target-build-tool config to bazel, but only sbt and mill are present
    val configuredUserConfig =
      UserConfiguration(targetBuildTool = Some("bazel"))
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala213}"
            |}
            |""".stripMargin,
        expectError = false,
        preInitialized = () => {
          server.server.tables.buildTool.reset()
          server.server.userConfigSync.updateUserConfiguration(configuredUserConfig)
        },
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Should show chooseBuildToolMessage since configured tool not found
          Messages.ChooseBuildTool
            .params(List(buildTool, alternativeBuildTool))
            .getMessage(),
          importBuildMessage,
        ).mkString("\n"),
      )
    } yield ()
  }

}

