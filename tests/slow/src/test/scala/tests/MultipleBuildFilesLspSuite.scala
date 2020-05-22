package tests

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Messages.ChooseBuildTool
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

class MultipleBuildFilesLspSuite
    extends BaseImportSuite("multiple-build-files") {

  // SBT will be the main tool for this test, which is what will be
  // chosen when the user is prompted in the test
  val buildTool: SbtBuildTool = SbtBuildTool(None, () => userConfig)

  val alternativeBuildTool: MillBuildTool = MillBuildTool(() => userConfig)

  def chooseBuildToolMessage: String =
    ChooseBuildTool.params(List(buildTool, alternativeBuildTool)).getMessage

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = None

  test("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala212}"
            |/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala212}"
            |}
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          chooseBuildToolMessage,
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.sbt") { text =>
        text + "\nversion := \"1.0.0\"\n"
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sbt")(identity)
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Ensure that after a choice was made, the user doesn't get re-prompted
          // to choose their build tool again
          importBuildChangesMessage,
          progressMessage
        ).mkString("\n")
      )
    }
  }

}
