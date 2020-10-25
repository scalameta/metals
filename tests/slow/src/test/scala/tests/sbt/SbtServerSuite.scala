package tests.sbt

import java.util.concurrent.TimeUnit

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.ScriptsAssertions

/**
 * TODO make a BaseLspSuite powered by sbt
 *
 * Note that this entire suite will probably change. For now the entire way we
 * have the Test server setup is to do a bloopInstall during initialize, but
 * for these we don't really want that. For now since the flow will always be
 * to favor Bloop to start, this is fine, and these tests all start with the
 * assumption that we are starting with Bloop, and then switching. Eventually,
 * We should probably have an entirely different BaseLspSuite powered by sbt.
 * That will be a huge task in intself, so for now, I've decided to simply
 * include these here and mark this as a TODO.
 */
class SbtServerSuite
    extends BaseImportSuite("sbt-server")
    with ScriptsAssertions {

  val preBspVersion = "1.3.13"
  // TODO after we go past 1.4.1 in Metals, this can just be pulled from
  // V.sbtVersion
  val bspVersion = "1.4.1"
  val scalaVersion = V.scala212
  val buildTool: SbtBuildTool = SbtBuildTool(None, () => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("too-old") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$preBspVersion
            |/build.sbt
            |scalaVersion := "${V.scala212}"
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear()
      _ = assertStatus(_.isInstalled)
      // Attempt to create a .bsp/sbt.json file
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig.id)
    } yield {
      assertNoDiff(
        client.workspaceShowMessages,
        Messages.NoSbtBspSupport.toString
      )
    }
  }

  test("generate") {
    def sbtBspConfig = workspace.resolve(".bsp/sbt.json")
    def sbtBspPlugin = workspace.resolve("project/MetalsSbtBsp.scala")
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$bspVersion
            |/build.sbt
            |scalaVersion := "${V.scala212}"
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          // since bloop is still the default
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ = assert(!sbtBspConfig.exists)
      _ = assert(!sbtBspPlugin.exists)
      // At this point, we want to use sbt server, so create the sbt.json file.
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig.id)
    } yield {
      assert(sbtBspPlugin.exists)
      assert(sbtBspConfig.exists)
    }
  }

  test("reload") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$bspVersion
            |/build.sbt
            |scalaVersion := "${V.scala212}"
            |""".stripMargin
      )
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig.id)
      // A bit obnoxious, but this taks a long time to connect in CI
      _ = Thread.sleep(TimeUnit.SECONDS.toMillis(20))
      _ <- server.didSave("build.sbt") { text =>
        s"""$text
           |ibraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
           |""".stripMargin
      }
      _ = {
        val msgs = client.workspaceErrorShowMessages
        assertNoDiff(msgs, Messages.ReloadProjectFailed.getMessage())
        client.showMessages.clear()
      }
      _ <- server.didSave("build.sbt") { _ =>
        s"""scalaVersion := "${V.scala212}"
           |libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
           |""".stripMargin
      }
      _ = {
        assert(client.workspaceErrorShowMessages.isEmpty())
      }
    } yield ()
  }
}
