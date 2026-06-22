package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.sys.process.Process

import scala.meta.internal.metals.Configs._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson

// Uncomment to run this test manually locally
@munit.IgnoreSuite
class ManualSuite extends BaseManualSuite {
  private val DefaultBspName = "Stripe Bazel"
  private val DefaultSyncTarget =
    "//src/test/java/com/stripe/log/loggingvalidation/server/rpcserver/ops/" +
      "loggingvalidationapi:tests_auto_gen_LoggingValidationOpTest"
  private val DefaultSourcePath =
    "src/test/java/com/stripe/log/loggingvalidation/server/rpcserver/ops/" +
      "RpcServerTestBase.java"
  private val DefaultDefinitionQueries = List(
    "DaggerRpcServerTestBase_RpcServerTestCompo@@nent.builder()",
    "LoggingValidationApiCli@@ent loggingValidationApiClient()",
  )

  private val home = System.getProperty("user.home")
  private val defaultBspLauncher =
    Paths.get(
      home,
      "stripe",
      "jetbrains-plugins",
      "bsp-server",
      "build",
      "install",
      "bsp-server",
      "bin",
      "stripe-bsp-server",
    )
  private val workspacePath: String =
    sys.props
      .get("metals.manual.workspace")
      .orElse(sys.props.get("metals.manual.zoolander"))
      .getOrElse(Paths.get("/pay", "src", "zoolander").toString())
  private val bspName =
    sys.props.getOrElse("metals.manual.bsp.name", DefaultBspName)
  private val configuredBspLauncher =
    sys.props.get("metals.manual.bsp.launcher")
  private val bspLauncher =
    configuredBspLauncher
      .map(path => Paths.get(path))
      .getOrElse(defaultBspLauncher)
  private val syncTarget =
    sys.props.getOrElse("metals.manual.bsp.syncTarget", DefaultSyncTarget)
  private val sourcePath =
    sys.props.getOrElse("metals.manual.source", DefaultSourcePath)
  private val definitionQueries =
    (1 to 5)
      .flatMap(index => sys.props.get(s"metals.manual.definitionQuery.$index"))
      .toList match {
      case Nil => DefaultDefinitionQueries
      case queries => queries
    }
  private val bspLanguages =
    sys.props
      .getOrElse("metals.manual.bsp.languages", "java,scala")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
  private val writeBspConnection =
    !sys.props.get("metals.manual.bsp.writeConnection").contains("false")
  private val runStripeBspSetup =
    sys.props.get("metals.manual.stripe-bsp.codegen") match {
      case Some("false") => false
      case Some("true") => true
      case _ => configuredBspLauncher.isEmpty && writeBspConnection
    }

  override def preferredBuildServer: Option[String] = Some(bspName)

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      preferredBuildServer = preferredBuildServer,
      fallbackClasspath = FallbackClasspathConfig.mbt,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      presentationCompilerDiagnostics = true,
      definitionIndexStrategy = DefinitionIndexStrategy.classpath,
      fallbackSourcepath = FallbackSourcepathConfig.allSources,
      compilerProgress = CompilerProgressConfig.enabled,
      referenceProvider = ReferenceProviderConfig.mbt,
      definitionProviders = DefinitionProviderConfig.default,
      scalaImportsPlacement = ScalaImportsPlacementConfig.smart,
      rangeFormattingProviders = RangeFormattingProviders.scalafmt,
      javacServicesOverrides = JavacServicesOverrides.default,
      buildOnChange = false,
      buildOnFocus = false,
    )

  private def setupBsp(workspace: AbsolutePath): Unit = {
    val installCommand = configuredBspLauncher
      .map(_ => s"Install the BSP launcher at $bspLauncher.")
      .getOrElse(
        "Run `cd ~/stripe/jetbrains-plugins && ./gradlew :bsp-server:installDist`."
      )
    if (writeBspConnection || runStripeBspSetup) {
      assert(
        Files.isExecutable(bspLauncher),
        s"Missing BSP launcher at $bspLauncher. $installCommand",
      )
    }

    val bspDir = workspace.resolve(".bsp").toNIO
    val workspacePath = workspace.toNIO.toString
    if (writeBspConnection) {
      Files.createDirectories(bspDir)
      val details = new BspConnectionDetails(
        bspName,
        List(
          bspLauncher.toString,
          "--workspace",
          workspacePath,
          "--sync-targets",
          syncTarget,
        ).asJava,
        "0.1.0",
        "2.2.0-M2",
        bspLanguages.asJava,
      )
      Files.writeString(
        bspDir.resolve("stripe-bsp.json"),
        new Gson().toJson(details),
        StandardCharsets.UTF_8,
      )
    }

    if (runStripeBspSetup) {
      runStripeBsp(workspace, "--run-sync")
      runStripeBsp(workspace, "--run-codegen")
    }
  }

  private def runStripeBsp(workspace: AbsolutePath, action: String): Unit = {
    val workspacePath = workspace.toNIO.toString
    val exitCode =
      Process(
        List(
          bspLauncher.toString,
          "--workspace",
          workspacePath,
          "--sync-targets",
          syncTarget,
          action,
        ),
        workspace.toFile,
      ).!
    assertEquals(exitCode, 0)
  }

  private def openGeneratedDefinition(
      server: TestingServer,
      filename: String,
      query: String,
  ): Future[Unit] =
    for {
      locations <- server.definitionSubstringQuery(filename, query)
      uri = locations
        .map(_.getUri)
        .find(uri =>
          uri.contains("bazel-out") ||
            uri.contains("generated") ||
            !uri.startsWith(server.workspace.toURI.toString)
        )
        .getOrElse(
          fail(
            s"Expected a generated definition for `$query`, got " +
              locations.map(_.getUri).mkString(", ")
          )
        )
      _ <- server.didOpenAndFocus(uri)
    } yield ()

  inDirectory(
    workspacePath,
    removeIndexMbt = false,
    onSetup = setupBsp,
  ).test("bsp-generated-source-diagnostics") { case (server, client) =>
    val path = sourcePath
    for {
      _ <- server.didOpenAndFocus(path)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- Future.traverse(definitionQueries) { query =>
        for {
          _ <- openGeneratedDefinition(server, path, query)
          _ <- server.didFocus(path)
          _ = assertNoDiff(client.workspaceDiagnostics, "")
        } yield ()
      }
    } yield ()
  }
}
