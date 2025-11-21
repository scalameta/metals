package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Configs.DefinitionIndexStrategy
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.ServerCommands.SyncFile
import scala.meta.internal.metals.UserConfiguration

// Uncomment to run this test manually locally
@munit.IgnoreSuite
class ManualSuite extends BaseManualSuite {
  val universe: String =
    Paths.get(System.getProperty("user.home"), "universe").toString()

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      definitionIndexStrategy = DefinitionIndexStrategy.classpath,
      fallbackClasspath = FallbackClasspathConfig.all3rdparty,
      fallbackScalaVersion = Some("2.12.20"),
    )

  inDirectory(
    universe,
    onSetup = { workspace =>
      // Clear the bazelbsp directory so that we get errors in the file during startup
      workspace.resolve(".bazelbsp").deleteRecursively
    },
    // removeCache = true,
  ).test("sync-file-lsp-command") { case (server, client) =>
    val path =
      "experimental/iulian.dragos/class-indexer/src/ClassIndexer.scala"
    for {
      _ <- server.didOpenAndFocus(path)
      // _ <- server.assertDefinition(
      //   path,
      //   "import com.google.gson.stream.JsonW@@riter;",
      //   """|.metals/readonly/dependencies/com.google.guava__guava__32.0.1-jre-sources.jar/com/google/common/annotations/VisibleForTesting.java:31:19: definition
      //      |public @interface VisibleForTesting {
      //      |                  ^^^^^^^^^^^^^^^^^
      //      |""".stripMargin,
      // )
      // _ <- server.didFocus(path)
      _ = assert((client.workspaceDiagnostics).nonEmpty)
      _ <- server.executeCommandUnsafe(SyncFile.id, Seq())
      // _ <- server.executeCommandUnsafe(ConnectBuildServer.id, Seq())
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }
}
