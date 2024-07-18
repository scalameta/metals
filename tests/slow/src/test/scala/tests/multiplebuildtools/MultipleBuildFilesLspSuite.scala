package tests.multiplebuildtools

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Messages.ChooseBuildTool
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite

class MultipleBuildFilesLspSuite
    extends BaseImportSuite("multiple-build-files") {

  // SBT will be the main tool for this test, which is what will be
  // chosen when the user is prompted in the test
  def buildTool: SbtBuildTool = SbtBuildTool(None, workspace, () => userConfig)

  def alternativeBuildTool: MillBuildTool =
    MillBuildTool(() => userConfig, workspace)

  def chooseBuildToolMessage: String =
    ChooseBuildTool.params(List(buildTool, alternativeBuildTool)).getMessage

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = None

  test("basic") {
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
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          chooseBuildToolMessage,
          importBuildMessage,
        ).mkString("\n"),
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
        // Ensure that after a choice was made, the user doesn't get re-prompted
        // to choose their build tool again
        importBuildChangesMessage,
      )
    }
  }

  test("custom-bsp") {
    cleanWorkspace()
    client.chooseBuildTool = actions =>
      actions
        .find(_.getTitle == "custom")
        .getOrElse(throw new Exception("no custom as build tool"))
    for {
      _ <- initialize(
        s"""|/.bsp/custom.json
            |${ScalaCli.scalaCliBspJsonContent(bspName = "custom")}
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = assert(server.server.bspSession.nonEmpty)
      _ = assert(server.server.bspSession.get.main.name == "custom")
    } yield ()
  }

  test("custom-bsp-2") {
    cleanWorkspace()
    client.chooseBuildTool = actions =>
      actions
        .find(_.getTitle == "Custom")
        .getOrElse(throw new Exception("no Custom as build tool"))
    for {
      _ <- initialize(
        s"""|/.bsp/custom.json
            |${ScalaCli.scalaCliBspJsonContent(bspName = "Custom")}
            |/.bsp/other-custom.json
            |${ScalaCli.scalaCliBspJsonContent(bspName = "Other custom")}
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = assert(server.server.bspSession.nonEmpty)
      _ = assert(server.server.bspSession.get.main.name == "Custom")
    } yield ()
  }

}
