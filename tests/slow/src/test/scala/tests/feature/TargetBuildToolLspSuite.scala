package tests.feature

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite

class TargetBuildToolLspSuite extends BaseImportSuite("target-build-tool") {

  private var testConfig: UserConfiguration = UserConfiguration()

  override def userConfig: UserConfiguration = testConfig

  // SBT will be the main tool for this test
  def buildTool: SbtBuildTool = SbtBuildTool(None, workspace, () => userConfig)

  def alternativeBuildTool: MillBuildTool =
    MillBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = None

  override def beforeEach(context: munit.BeforeEach): Unit = {
    // Set testConfig BEFORE the server is created
    if (context.test.name.contains("sbt")) {
      testConfig = UserConfiguration(targetBuildTool = Some("sbt"))
    } else if (context.test.name.contains("mill")) {
      testConfig = UserConfiguration(targetBuildTool = Some("mill"))
    } else if (context.test.name.contains("not-found")) {
      testConfig = UserConfiguration(targetBuildTool = Some("bazel"))
    }
    super.beforeEach(context)
  }

  test("target-build-tool-sbt") {
    cleanWorkspace()
    // Note: targetBuildTool in testConfig is set to sbt in beforeEach
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
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Should NOT show chooseBuildToolMessage since config specifies sbt
          importBuildMessage
        ).mkString("\n"),
      )
      _ <- server.server.indexingPromise.future
      // Verify that sbt was chosen
      _ = assert(
        server.server.tables.buildTool.selectedBuildTool().contains("sbt")
      )
    } yield ()
  }

  test("target-build-tool-mill") {
    cleanWorkspace()
    // Note: targetBuildTool in testConfig is set to mill in beforeEach
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
        expectError =
          true, // BloopImportInitializer runs sbt which won't work with mill
      )
      // Verify that mill was chosen (even though build didn't complete)
      _ = assert(
        server.server.tables.buildTool.selectedBuildTool().contains("mill")
      )
    } yield ()
  }

  test("target-build-tool-not-found") {
    cleanWorkspace()
    // Note: targetBuildTool in testConfig is set to bazel in beforeEach
    // Since bazel doesn't exist, user will be prompted to choose
    client.chooseBuildTool = actions =>
      actions
        .find(_.getTitle == "sbt")
        .getOrElse(actions.head)
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
