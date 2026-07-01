package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.util.Try

import scala.meta.internal.metals.Configs._
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson

class ManualSuite extends BaseManualSuite {
  private case class QuerySpec(
      file: String,
      query: String,
      minLocations: Int,
  )
  private case class DefinitionSpec(
      file: String,
      query: String,
      generatedOnly: Boolean,
  )

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
    prop("metals.manual.workspace")
      .orElse(prop("metals.manual.zoolander"))
      .getOrElse(Paths.get("/pay", "src", "zoolander").toString())
  private val bspName =
    prop("metals.manual.bsp.name").getOrElse(DefaultBspName)
  private val configuredBspLauncher =
    prop("metals.manual.bsp.launcher")
  private val bspLauncher =
    configuredBspLauncher
      .map(path => Paths.get(path))
      .getOrElse(defaultBspLauncher)
  private val syncTarget =
    prop("metals.manual.bsp.syncTarget").getOrElse(DefaultSyncTarget)
  private val sourcePath =
    prop("metals.manual.source").getOrElse(DefaultSourcePath)
  private val extraOpenFiles =
    indexedProperties("metals.manual.openFile")
  private val definitionQueries =
    (1 to 5)
      .flatMap(index => prop(s"metals.manual.definitionQuery.$index"))
      .toList match {
      case Nil if sourcePath == DefaultSourcePath => DefaultDefinitionQueries
      case Nil                                    => Nil
      case queries                                => queries
    }
  private val definitionSpecs =
    definitionQueries.zipWithIndex.map { case (query, offset) =>
      val index = offset + 1
      DefinitionSpec(
        prop(s"metals.manual.definitionQuery.$index.file").getOrElse(sourcePath),
        query,
        !prop(s"metals.manual.definitionQuery.$index.generatedOnly")
          .contains("false"),
      )
    }
  private val referenceQueries =
    querySpecs("metals.manual.referenceQuery", sourcePath)
  private val implementationQueries =
    querySpecs("metals.manual.implementationQuery", sourcePath)
  private val bspLanguages =
    prop("metals.manual.bsp.languages")
      .getOrElse("java,scala")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
  private val writeBspConnection =
    !prop("metals.manual.bsp.writeConnection").contains("false")
  private val runStripeBspSetup =
    prop("metals.manual.stripe-bsp.codegen") match {
      case Some("false") => false
      case Some("true")  => true
      case _             => configuredBspLauncher.isEmpty && writeBspConnection
    }
  private val awaitEditorNotifications =
    !prop("metals.manual.awaitEditorNotifications").contains("false")
  private val afterOpenDelay =
    prop("metals.manual.afterOpenDelay").map(Duration(_)).getOrElse(Duration.Zero)
  private val afterDefinitionOpenDelay =
    prop("metals.manual.afterDefinitionOpenDelay")
      .map(Duration(_))
      .getOrElse(Duration.Zero)
  private val cleanDiagnosticsTimeout =
    prop("metals.manual.cleanDiagnosticsTimeout")
      .map(Duration(_))
      .getOrElse(Duration.Zero)
  private val javaSymbolLoader =
    prop("metals.manual.javaSymbolLoader") match {
      case Some("javac-sourcepath")         => JavaSymbolLoaderConfig.javacSourcepath
      case Some("turbine-classpath") | None =>
        JavaSymbolLoaderConfig.turbineClasspath
      case Some(other) =>
        sys.error(s"Invalid metals.manual.javaSymbolLoader: $other")
    }

  override def preferredBuildServer: Option[String] = Some(bspName)

  override def munitIgnore: Boolean =
    !prop("metals.manual.enabled").contains("true")

  override def munitTimeout: Duration =
    prop("metals.manual.timeout").map(Duration(_)).getOrElse(super.munitTimeout)

  override def awaitInitialized: Boolean =
    !prop("metals.manual.awaitInitialized").contains("false")

  override def buildServerConnectionTimeout: Duration =
    prop("metals.manual.buildServerConnectionTimeout")
      .map(Duration(_))
      .getOrElse(super.buildServerConnectionTimeout)

  override def mtagsResolver: MtagsResolver =
    new TestMtagsResolver(checkCoursier = false)

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      preferredBuildServer = preferredBuildServer,
      fallbackClasspath = FallbackClasspathConfig.mbt,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = javaSymbolLoader,
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
    manualLog(
      s"workspace=${workspace.toNIO} bspName=$bspName writeBspConnection=$writeBspConnection runStripeBspSetup=$runStripeBspSetup"
    )

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
      manualLog(s"wrote .bsp/stripe-bsp.json for $syncTarget")
    }

    if (runStripeBspSetup) {
      runStripeBsp(workspace, "--run-sync")
      runStripeBsp(workspace, "--run-codegen")
    }
  }

  private def runStripeBsp(workspace: AbsolutePath, action: String): Unit = {
    val workspacePath = workspace.toNIO.toString
    manualLog(s"stripe-bsp $action start for $syncTarget")
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
    manualLog(s"stripe-bsp $action done")
  }

  private def openDefinition(
      server: TestingServer,
      filename: String,
      query: String,
      generatedOnly: Boolean,
  ): Future[Unit] =
    for {
      locations <- logged(s"definition $filename $query")(
        server.definitionSubstringQuery(filename, query)
      )
      uri = locations
        .map(_.getUri)
        .find(uri =>
          !generatedOnly ||
            uri.contains("bazel-out") ||
            uri.contains("generated") ||
            !uri.startsWith(server.workspace.toURI.toString)
        )
        .getOrElse(
          fail(
            s"Expected a definition for `$query`, got " +
              locations.map(_.getUri).mkString(", ")
          )
        )
      _ = manualLog(s"definition selected $uri")
      _ <- openAndFocus(server, uri, "definition")
      _ <- sleep(afterDefinitionOpenDelay)
    } yield ()

  inDirectory(
    workspacePath,
    removeIndexMbt = false,
    onSetup = setupBsp,
  ).test("bsp-generated-source-diagnostics") { case (server, client) =>
    val path = sourcePath
    for {
      _ <- openAndFocus(server, path, "source")
      _ <- Future.traverse(extraOpenFiles) { file =>
        openAndFocus(server, file, "extra file")
      }
      _ <- sleep(afterOpenDelay)
      _ <- logged(s"focus source $path")(server.didFocus(path))
      _ <- assertCleanDiagnostics(client, "before navigation")
      _ <- Future.traverse(definitionSpecs) { spec =>
        for {
          _ <- openDefinition(server, spec.file, spec.query, spec.generatedOnly)
          _ <- logged(s"return focus $path")(server.didFocus(path))
          _ <- assertCleanDiagnostics(client, s"after definition ${spec.query}")
        } yield ()
      }
      _ <- Future.traverse(referenceQueries) { spec =>
        for {
          locations <- logged(s"references ${spec.file} ${spec.query}")(
            server.referencesSubquery(spec.file, spec.query)
          )
          _ = assert(
            locations.size >= spec.minLocations,
            s"Expected at least ${spec.minLocations} references for " +
              s"`${spec.query}` in `${spec.file}`, got ${locations.size}",
          )
          _ = manualLog(s"references found ${locations.size}")
          _ <- assertCleanDiagnostics(client, s"after references ${spec.query}")
        } yield ()
      }
      _ <- Future.traverse(implementationQueries) { spec =>
        for {
          locations <- logged(s"implementations ${spec.file} ${spec.query}")(
            server.implementationsSubquery(spec.file, spec.query)
          )
          _ = assert(
            locations.size >= spec.minLocations,
            s"Expected at least ${spec.minLocations} implementations for " +
              s"`${spec.query}` in `${spec.file}`, got ${locations.size}",
          )
          _ = manualLog(s"implementations found ${locations.size}")
          _ <- assertCleanDiagnostics(client, s"after implementations ${spec.query}")
        } yield ()
      }
    } yield ()
  }

  private def indexedProperties(prefix: String): List[String] =
    (1 to 10).flatMap(index => prop(s"$prefix.$index")).toList

  private def openAndFocus(
      server: TestingServer,
      path: String,
      label: String,
  ): Future[Unit] =
    if (awaitEditorNotifications) {
      logged(s"open $label $path")(server.didOpenAndFocus(path))
    } else {
      manualLog(s"send: open $label $path")
      server.didOpen(path)
      server.didFocus(path)
      manualLog(s"sent: open $label $path")
      Future.unit
    }

  private def sleep(duration: Duration): Future[Unit] =
    if (duration.length <= 0) Future.unit
    else logged(s"sleep $duration")(Future(Thread.sleep(duration.toMillis)))

  private def assertCleanDiagnostics(
      client: TestingClient,
      label: String,
  ): Future[Unit] = Future {
    manualLog(s"assert diagnostics $label")
    if (cleanDiagnosticsTimeout.length <= 0) {
      assertNoDiff(client.workspaceDiagnostics, "")
    } else {
      val deadline = System.nanoTime() + cleanDiagnosticsTimeout.toNanos
      var diagnostics = client.workspaceDiagnostics
      while (diagnostics.nonEmpty && System.nanoTime() < deadline) {
        Thread.sleep(1000)
        diagnostics = client.workspaceDiagnostics
      }
      if (diagnostics.isEmpty) {
        manualLog(s"diagnostics clean $label")
      } else {
        assertNoDiff(diagnostics, "")
      }
    }
  }

  private def querySpecs(prefix: String, defaultFile: String): List[QuerySpec] =
    (1 to 10).flatMap { index =>
      prop(s"$prefix.$index").map { query =>
        val file = prop(s"$prefix.$index.file").getOrElse(defaultFile)
        val minLocations = prop(s"$prefix.$index.min")
          .flatMap(value => Try(value.toInt).toOption)
          .getOrElse(1)
        QuerySpec(file, query, minLocations)
      }
    }.toList

  private def prop(name: String): Option[String] =
    sys.props
      .get(name)
      .orElse(sys.env.get(name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase))
}
