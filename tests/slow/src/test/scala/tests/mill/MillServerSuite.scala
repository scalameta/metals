package tests.mill

import java.util.concurrent.TimeUnit

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

/**
 * Basic suite to ensure that a connection to a Mill server can be made.
 */
class MillServerSuite
    extends BaseImportSuite("mill-server", MillServerInitializer) {

  val preBspVersion = "0.9.10"
  val supportedBspVersion = V.millVersion
  val scalaVersion = V.scala213
  val buildTool: MillBuildTool = MillBuildTool(() => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MillDigest.current(workspace)

  test("too-old") {
    cleanWorkspace()
    writeLayout(MillBuildLayout("", V.scala213, preBspVersion))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage
        ).mkString("\n"),
      )
      _ = client.messageRequests.clear()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assertNoDiff(
        client.workspaceShowMessages,
        Messages.NoBspSupport.toString,
      )
    }
  }

  val versionsToTest: List[String] =
    List("0.10.0", "0.10.8", supportedBspVersion)

  versionsToTest.foreach(testGenerationAndConnection)

  private def testGenerationAndConnection(version: String) = {
    test(s"generate-and-connect-$version") {
      def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
      cleanWorkspace()
      writeLayout(MillBuildLayout("", V.scala213, version))
      for {
        _ <- server.initialize()
        _ <- server.initialized()
        _ = assertNoDiff(
          client.workspaceMessageRequests,
          // Project has no .bloop directory so user is asked to "import via bloop"
          // since bloop is still the default
          importBuildMessage,
        )
        _ = client.messageRequests.clear() // restart
        _ = assert(!millBspConfig.exists)
        // At this point, we want to use mill-bsp server, so create the mill-bsp.json file.
        _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
        // We need to wait a bit just to ensure the connection is made
        _ <- server.waitFor(TimeUnit.SECONDS.toMillis(1))
      } yield {
        assert(millBspConfig.exists)
        server.assertBuildServerConnection()
      }
    }

  }
}
