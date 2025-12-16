package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Configs.DefinitionProviderConfig
import scala.meta.internal.metals.UserConfiguration

// Uncomment to run this test manually locally
@munit.IgnoreSuite
class ManualSuite extends BaseManualSuite {
  val universe: String =
    Paths.get(System.getProperty("user.home"), "universe").toString()

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      definitionProviders = DefinitionProviderConfig(List("protobuf"))
    )

  inDirectory(
    universe
  ).test("protobuf-defn") { case (server, client) =>
    val path = "example/Example.scala"
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
      // _ = assert((client.workspaceDiagnostics).nonEmpty)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.definitionSubstringQuery(path, "  Ses@@sion,")
      // _ <- server.executeCommandUnsafe(ConnectBuildServer.id, Seq())
    } yield ()
  }
}
