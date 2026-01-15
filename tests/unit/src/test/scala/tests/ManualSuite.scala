package tests

import java.nio.file.Paths

import scala.meta.internal.metals.Configs._
import scala.meta.internal.metals.UserConfiguration

// Uncomment to run this test manually locally
@munit.IgnoreSuite
class ManualSuite extends BaseManualSuite {
  val trino: String =
    Paths.get(System.getProperty("user.home"), "trino").toString()

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      preferredBuildServer = Some("bloop"),
      fallbackClasspath = FallbackClasspathConfig.mbt,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      presentationCompilerDiagnostics = true,
      definitionIndexStrategy = DefinitionIndexStrategy.classpath,
      javaOutlineProvider = JavaOutlineProviderConfig.javac,
      fallbackSourcepath = FallbackSourcepathConfig.allSources,
      compilerProgress = CompilerProgressConfig.enabled,
      referenceProvider = ReferenceProviderConfig.mbt,
      definitionProviders = DefinitionProviderConfig.protobuf,
      scalaImportsPlacement = ScalaImportsPlacementConfig.smart,
      rangeFormattingProviders = RangeFormattingProviders.scalafmt,
      javacServicesOverrides = JavacServicesOverrides.default,
      buildOnChange = false,
      buildOnFocus = false,
      // definitionProviders = DefinitionProviderConfig(List("protobuf"))
    )

  inDirectory(
    trino
  ).test("oss-test") { case (server, client) =>
    val path =
      "core/trino-main/src/main/java/io/trino/operator/join/JoinHashSupplier.java"
    for {
      _ <- server.didOpenAndFocus(path)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.assertDefinition(
        path,
        "import com.google.common.collect.Immutab@@leList;",
        """|guava-33.5.0-jre-sources.jar!/com/google/common/collect/ImmutableList.java:65:23: definition
           |public abstract class ImmutableList<E> extends ImmutableCollection<E>
           |                      ^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
