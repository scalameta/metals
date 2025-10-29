package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Configs.DefinitionIndexStrategy
import scala.meta.internal.metals.UserConfiguration

// Uncomment to run this test manually locally
@munit.IgnoreSuite
class ManualSuite extends BaseManualSuite {
  val universe: String =
    Paths.get(System.getProperty("user.home"), "universe").toString()

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      definitionIndexStrategy = DefinitionIndexStrategy.classpath
    )

  inDirectory(
    universe
    // removeCache = true,
  ).test("definition-classpath") { case (server, client) =>
    val path =
      "elastic-spark/lakeflow/assets/list/impl/InMemorySortingAssetFetcher.scala"
    for {
      _ <- server.didOpen(path)
      _ <- server.didFocus(path)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.assertDefinition(
        path,
        "import com.google.common.annotations.VisibleForTes@@ting",
        """|.metals/readonly/dependencies/com.google.guava__guava__32.0.1-jre-sources.jar/com/google/common/annotations/VisibleForTesting.java:31:19: definition
           |public @interface VisibleForTesting {
           |                  ^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
