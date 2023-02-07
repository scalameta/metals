package tests.scalacli

import tests.BaseImportSuite
import tests.ScalaCliServerInitializer
import scala.concurrent.Promise
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.ScalaCliDigest
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import tests.ScalaCliBuildLayout

class ScalaCliServerSuite
    extends BaseImportSuite("scala-cli-server", ScalaCliServerInitializer) {
  val buildTool = ScalaCliBuildTool(() => userConfig)
  val scalaCliLauncher =
    userConfig.scalaCliLauncher.getOrElse(buildTool.executableName)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = ScalaCliDigest.current(workspace)

  test("generate") {
    def scalaCliBspConfig = workspace.resolve(".bsp/scala-cli.json")
    def isScalaCliConfigValid =
      scalaCliBspConfig.readText.contains(scalaCliLauncher)
    def scalaBuildDirectory = workspace.resolve(".scala-build")
    cleanWorkspace()
    writeLayout(ScalaCliBuildLayout(scalaCliLauncher, workspace.toString))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = client.messageRequests.clear()
      _ = server.server.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      _ <- server.server.buildServerPromise.future
    } yield {
      assert(scalaCliBspConfig.exists)
      assert(isScalaCliConfigValid)
      assert(scalaBuildDirectory.exists)
      server.assertBuildServerConnection()
    }
  }
}
