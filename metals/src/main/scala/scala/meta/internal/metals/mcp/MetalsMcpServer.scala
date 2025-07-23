package scala.meta.internal.metals.mcp

import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Arrays
import java.util.function.BiFunction
import java.util.List as JList
import java.util.Map as JMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.debug.{
  DebugDiscovery,
  DebugOutputManager,
  DebugProvider,
  DebugServer,
  DebugStep,
  Debugger,
}
import scala.meta.internal.metals.mcp.McpPrinter.*
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.io.AbsolutePath
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.StatusCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.AsyncResourceSpecification
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Content
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.Resource
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.InstanceHandle
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import reactor.core.publisher.Mono

import scala.concurrent.Promise
import org.eclipse.lsp4j.debug.EvaluateResponse
import com.github.andyglow.jsonschema.AsCirce.*
import io.circe.Decoder
import io.circe.generic.semiauto.*
import json.schema.description

import MetalsMcpServer.*

class MetalsMcpServer(
    queryEngine: McpQueryEngine,
    projectPath: AbsolutePath,
    compilations: Compilations,
    focusedDocument: () => Option[AbsolutePath],
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    mcpTestRunner: McpTestRunner,
    clientName: String,
    projectName: String,
    languageClient: LanguageClient,
    connectionProvider: ConnectionProvider,
    scalaVersionSelector: ScalaVersionSelector,
    debugProvider: DebugProvider,
    debugDiscovery: DebugDiscovery,
    debugOutputManager: DebugOutputManager,
)(implicit
    ec: ExecutionContext
) extends Cancelable {

  private val client =
    Client.allClients.find(_.names.contains(clientName)).getOrElse(NoClient)

  val tracePrinter: Option[PrintWriter] =
    Trace.setupTracePrinter("mcp", projectPath)

  private val objectMapper = new ObjectMapper()

  private def createContent(text: String): JList[Content] = {
    Arrays.asList(new TextContent(text))
  }

  private val cancelable = new MutableCancelable()

  private val sseEndpoint = "/sse"

  def run(): Unit = {
    val servlet =
      new LoggingServletTransportProvider(
        objectMapper,
        "/",
        sseEndpoint,
        tracePrinter,
      )

    val capabilities = ServerCapabilities
      .builder()
      .tools(true) // Tool support with list changes notifications
      .resources(true, true) // Resource support with list changes notifications
      .logging() // Enable logging support (enabled by default with logging level INFO)
      .build();

    // Create server with configuration
    val asyncServer = McpServer
      .async(servlet)
      .serverInfo("scala-mcp-server", "0.1.0")
      .capabilities(capabilities)
      .build()

    cancelable.add(() => {
      asyncServer.close()
    })

    // Register tools
    val tools = List(
      createFileCompileTool(),
      createCompileModuleTool(),
      createCompileTool(),
      createTestTool(),
      createGlobSearchTool(),
      createTypedGlobSearchTool(),
      createInspectTool(),
      createGetDocsTool(),
      createGetUsagesTool(),
      importBuildTool(),
      createFindDepTool(),
      createListModulesTool(),
      createDebugMainTool(),
      createDebugTestTool(),
      createDebugAttachTool(),
      createDebugSessionsTool(),
      createDebugPauseTool(),
      createDebugContinueTool(),
      createDebugStepTool(),
      createDebugEvaluateTool(),
      createDebugVariablesTool(),
      createDebugBreakpointsTool(),
      createDebugTerminateTool(),
      createDebugThreadsTool(),
      createDebugStackTraceTool(),
      createDebugOutputTool(),
    )
    tools.foreach(asyncServer.addTool(_).subscribe())

    val resources = List(
      createMetalsLogsResource(),
      createDebugOutputResource(),
      createDebugOutputResource(suffix = "{query}"),
    )
    resources.foreach(asyncServer.addResource(_).subscribe())

    // Log server initialization
    asyncServer.loggingNotification(
      LoggingMessageNotification
        .builder()
        .level(LoggingLevel.INFO)
        .logger("scala-mcp-server")
        .data("Server initialized")
        .build()
    )

    // serve servlet
    val servletDeployment = Servlets
      .deployment()
      .setClassLoader(classOf[MetalsMcpServer].getClassLoader())
      .setContextPath("/")
      .setDeploymentName("sse-server.war")
      .addServlets(
        Servlets
          .servlet(
            "SseServlet",
            classOf[HttpServletSseServerTransportProvider],
            () =>
              new InstanceHandle[HttpServletSseServerTransportProvider] {
                override def getInstance()
                    : HttpServletSseServerTransportProvider = servlet
                override def release(): Unit = { servlet.close() }

              },
          )
          .setAsyncSupported(true)
          .addMapping("/*")
      )

    val manager = Servlets.defaultContainer()
    val deployment = manager.addDeployment(servletDeployment)
    deployment.deploy()

    // Read port from the default config file in .metals/ directory
    def readPort(client: Client) =
      McpConfig.readPort(projectPath, projectName, client)
    val savedClientConfigPort = readPort(client)
    val savedConfigPort = savedClientConfigPort.orElse(readPort(NoClient))
    val undertowServer = Undertow
      .builder()
      .addHttpListener(savedConfigPort.getOrElse(0), "localhost")
      .setHandler(deployment.start())
      .build()
    undertowServer.start()

    val listenerInfo = undertowServer.getListenerInfo()
    val port =
      listenerInfo.get(0).getAddress().asInstanceOf[InetSocketAddress].getPort()

    if (savedConfigPort.isEmpty) {
      McpConfig.writeConfig(port, projectName, projectPath, NoClient)
    }

    if (savedClientConfigPort.isEmpty) {
      McpConfig.writeConfig(port, projectName, projectPath, client)
    }

    languageClient.showMessage(
      new MessageParams(
        MessageType.Info,
        s"Metals MCP server started on port: $port. Refresh connection if needed.",
      )
    )

    scribe.info(s"Metals MCP server started on port: $port.")

    cancelable.add(() => undertowServer.stop())
  }

  override def cancel(): Unit = {
    // Remove the config so that next time the editor will only connect after mcp server is started
    if (client != NoClient && client.shouldCleanUpServerEntry) {
      McpConfig.deleteConfig(projectPath, projectName, client)
    }
    cancelable.cancel()
  }

  /**
   * Schedule the debugger initialization to happen asynchronously after it becomes available.
   * This doesn't block the MCP response, allowing the test client to connect.
   */
  private def scheduleDebuggerInitialization(
      session: DebugServer,
      isAttach: Boolean = false,
      initialBreakpoints: List[Map[String, Object]] = Nil,
  ): Future[Unit] = {
    val _ = isAttach // Mark as used to avoid compiler warning
    def attemptInitialize(attempt: Int, maxAttempts: Int = 50): Future[Unit] = {
      debugProvider.getOrCreateDebugger(session.id) match {
        case Some(debugger) =>

          for {
            _ <- debugger.initialize("mcp-adapter")
            _ <- debugger.launch(debug = true)
            _ <- {
              // Group breakpoints by source file
              val regularBreakpoints = initialBreakpoints
              val breakpointsBySource =
                regularBreakpoints.groupBy(_.get("source"))
              Future.sequence(breakpointsBySource.toList.map {
                case (sourceUri, bps) =>
                  val source = new org.eclipse.lsp4j.debug.Source()
                  // Ensure paths are absolute - resolve relative paths against project path
                  val resolvedPath = sourceUri.map(_.toString).map { path =>
                    // Check if it's already an absolute path or URI
                    if (
                      path.startsWith("file:") || path.startsWith("jar:") ||
                      path.startsWith("/")
                    ) {
                      path
                    } else {
                      // It's a relative path - resolve against project path
                      // Use toNIO to get the java.nio.file.Path, then resolve and convert back
                      projectPath.resolve(path).toURI.toString
                    }
                  }
                  source.setPath(resolvedPath.orNull)

                  val sourceBreakpoints = bps.map { bp =>
                    val b = new org.eclipse.lsp4j.debug.SourceBreakpoint()
                    b.setLine(
                      bp("line").asInstanceOf[java.lang.Number].intValue()
                    )
                    b.setCondition(bp.get("condition").map(_.toString).orNull)
                    b.setLogMessage(
                      bp.get("logMessage").map(_.toString).orNull
                    )
                    b
                  }.toArray

                  debugger.setBreakpoints(source, sourceBreakpoints)
              })
            }
            _ <- debugger.configurationDone
          } yield ()
        case None if attempt < maxAttempts =>
          Future {
            Thread.sleep(200)
          }.flatMap(_ => attemptInitialize(attempt + 1, maxAttempts))
        case None =>
          scribe.error(
            s"[MCP] Debugger not available for session ${session.id} after $maxAttempts attempts"
          )
          Future.failed(
            new Exception(
              s"Debugger not available for session ${session.id} after $maxAttempts attempts"
            )
          )
      }
    }

    // Start the initialization attempts
    attemptInitialize(1)
  }

  def javaObjectToJson(obj: Object): io.circe.Json = {
    import io.circe.Json
    import scala.jdk.CollectionConverters._
    obj match {
      case null => Json.Null
      case s: String => Json.fromString(s)
      case n: java.lang.Number => Json.fromBigDecimal(BigDecimal(n.toString))
      case b: java.lang.Boolean => Json.fromBoolean(b)
      case m: java.util.Map[_, _] =>
        Json.obj(m.asScala.toSeq.collect { case (k: String, v: Object) =>
          k -> javaObjectToJson(v)
        }: _*)
      case l: java.util.List[Object] =>
        Json.arr(l.asScala.toSeq.map(javaObjectToJson): _*)
      case other => Json.fromString(other.toString)
    }
  }

  private def createAsyncTool[T: Decoder](
      name: String,
      description: String,
      handler: T => Future[CallToolResult],
  )(implicit schemaGenerator: json.Schema[T]): AsyncToolSpecification = {
    val jsonSchemaVersion = json.schema.Version.Draft12(name)
    val schemaString = schemaGenerator.asCirce(jsonSchemaVersion).spaces2
    new AsyncToolSpecification(
      new Tool(
        name,
        description,
        schemaString,
      ),
      withErrorHandling { (exchange, arguments) =>
        Future
          .fromTry(javaObjectToJson(arguments).as[T].toTry)
          .flatMap(handler)
          .recover { case e: Exception =>
            new CallToolResult(
              createContent(s"Failed to run $name: ${e.getMessage}"),
              true,
            )
          }
          .toMono
      },
    )
  }

  private def importBuildTool(): AsyncToolSpecification = {
    createAsyncTool[ImportBuildArgs](
      "import-build",
      "Import the build to IDE. Should be performed after any build changes, e.g. adding dependencies or any changes in build.sbt.",
      _ =>
        connectionProvider
          .slowConnectToBuildServer(forceImport = true)
          .map {
            case BuildChange.None =>
              new CallToolResult(createContent("No changes detected"), false)
            case BuildChange.Reconnected =>
              new CallToolResult(
                createContent("Reconnected to build server"),
                false,
              )
            case BuildChange.Reloaded =>
              new CallToolResult(createContent("Build reloaded"), false)
            case BuildChange.Failed =>
              new CallToolResult(
                createContent("Failed to reimport build."),
                false,
              )
            case BuildChange.Cancelled =>
              new CallToolResult(
                createContent("Reimport cancelled by the user."),
                false,
              )
          },
    )
  }

  private def createCompileTool(): AsyncToolSpecification = {
    createAsyncTool[CompileFullArgs](
      "compile-full",
      "Compile the whole Scala project",
      _ =>
        compilations
          .cascadeCompile(buildTargets.allBuildTargetIds)
          .map { _ =>
            val errors = diagnostics.allDiagnostics.show(projectPath)
            val content =
              if (errors.isEmpty) "Compilation successful."
              else s"Compilation failed with errors:\n$errors"
            new CallToolResult(createContent(content), false)
          },
    )
  }

  private def createFileCompileTool(): AsyncToolSpecification = {
    createAsyncTool[CompileFileArgs](
      "compile-file",
      "Compile a chosen Scala file",
      arguments => {
        val path = arguments.getFileInFocus
        if (path.exists) {
          compilations
            .compileFile(path)
            .map {
              case c if c.getStatusCode == StatusCode.CANCELLED =>
                new CallToolResult(
                  createContent("Compilation cancelled or incorrect file path"),
                  false,
                )
              case _ =>
                lazy val buildTarget = buildTargets.inverseSources(path)

                def inFileErrors = {
                  val errors = diagnostics.forFile(path).show()
                  if (errors.isEmpty) None
                  else Some(s"Found errors in $path:\n$errors")
                }

                def inModuleErrors =
                  for {
                    bt <- buildTarget
                    errors <- this.inModuleErrors(bt)
                  } yield {
                    s"No errors in the file, but found compile errors in the module:\n$errors"
                  }

                def inUpstreamModulesErrors =
                  for {
                    bt <- buildTarget
                    errors <- upstreamModulesErros(bt, "file")
                  } yield errors

                val content = inFileErrors
                  .orElse(inModuleErrors)
                  .orElse(inUpstreamModulesErrors)
                  .getOrElse("Compilation successful.")

                new CallToolResult(createContent(content), false)
            }
        } else {
          Future
            .successful(
              new CallToolResult(
                createContent(s"Error: File not found: $path"),
                true,
              )
            )
        }
      },
    )
  }

  private def createCompileModuleTool(): AsyncToolSpecification = {
    createAsyncTool[CompileModuleArgs](
      "compile-module",
      "Compile a chosen Scala module",
      arguments =>
        Future {
          (buildTargets.allScala ++ buildTargets.allJava).find(
            _.displayName == arguments.module
          ) match {
            case Some(target) =>
              val result = inModuleErrors(target.id)
                .map(errors => s"Found errors in the module:\n$errors")
                .orElse(upstreamModulesErros(target.id, "module"))
                .getOrElse("Compilation successful.")
              new CallToolResult(createContent(result), false)
            case None =>
              new CallToolResult(
                createContent(s"Error: Module not found: ${arguments.module}"),
                true,
              )
          }
        },
    )
  }

  private def inModuleErrors(buildTarget: BuildTargetIdentifier) = {
    if (diagnostics.hasCompilationErrors(buildTarget)) {
      val errors =
        diagnostics.allDiagnostics.filter { case (path, _) =>
          buildTargets.inverseSources(path).contains(buildTarget)
        }
      Some(errors.show(projectPath))
    } else None
  }

  private def upstreamModulesErros(
      buildTarget: BuildTargetIdentifier,
      fileOrModule: String,
  ) = {
    val upstreamModules = diagnostics
      .upstreamTargetsWithCompilationErrors(buildTarget)
    if (upstreamModules.nonEmpty) {
      val modules = upstreamModules
        .flatMap(buildTargets.jvmTarget)
        .map(_.displayName)
        .mkString("\n", "\n", "")
      Some(
        s"Failed to compile the $fileOrModule, since there compile errors in upstream modules: $modules"
      )
    } else None
  }

  private def createTestTool(): AsyncToolSpecification = {
    createAsyncTool[TestArgs](
      "test",
      "Run Scala test suite",
      arguments => {
        val result = mcpTestRunner.runTests(
          arguments.testClass,
          arguments.testFile
            .map(path => AbsolutePath(Path.of(path))(projectPath)),
          arguments.verbose.getOrElse(false),
        )
        result match {
          case Right(value) =>
            value.map(content =>
              new CallToolResult(createContent(content), false)
            )
          case Left(error) =>
            Future.successful(
              new CallToolResult(createContent(s"Error: $error"), true)
            )
        }
      },
    )
  }

  private def createGlobSearchTool(): AsyncToolSpecification = {
    createAsyncTool[GlobSearchArgs](
      "glob-search",
      "Search for symbols using glob pattern",
      arguments => {
        queryEngine
          .globSearch(arguments.query, Set.empty, arguments.getFileInFocus)
          .map(result =>
            new CallToolResult(
              createContent(result.map(_.show).mkString("\n")),
              false,
            )
          )
      },
    )
  }

  private def createTypedGlobSearchTool(): AsyncToolSpecification = {
    createAsyncTool[TypedGlobSearchArgs](
      "typed-glob-search",
      "Search for symbols by type using glob pattern",
      arguments => {
        val symbolTypes = arguments.symbolType
          .flatMap(s => SymbolType.values.find(_.name == s))
          .toSet

        queryEngine
          .globSearch(arguments.query, symbolTypes, arguments.getFileInFocus)
          .map(result =>
            new CallToolResult(
              createContent(result.map(_.show).mkString("\n")),
              false,
            )
          )
      },
    )
  }

  private def createInspectTool(): AsyncToolSpecification = {
    createAsyncTool[InspectArgs](
      "inspect",
      """|Inspect a chosen Scala symbol.
         |For packages, objects and traits returns list of members.
         |For classes returns list of members and constructors.
         |For methods returns signatures of all overloaded methods.""".stripMargin,
      arguments => {
        queryEngine
          .inspect(arguments.getFqcn, arguments.getFileInFocus)
          .map(result =>
            new CallToolResult(
              createContent(result.show),
              false,
            )
          )
      },
    )
  }

  private def createGetDocsTool(): AsyncToolSpecification = {
    createAsyncTool[GetDocsArgs](
      "get-docs",
      "Get documentation for a chosen Scala symbol",
      arguments =>
        Future {
          queryEngine.getDocumentation(arguments.getFqcn) match {
            case Some(result) =>
              new CallToolResult(createContent(result.show), false)
            case None =>
              new CallToolResult(
                createContent("Error: Symbol not found"),
                false,
              )
          }
        },
    )
  }

  private def createGetUsagesTool(): AsyncToolSpecification = {
    createAsyncTool[GetUsagesArgs](
      "get-usages",
      "Get usages for a chosen Scala symbol. Returns list of files with line numbers.",
      arguments => {
        Future {
          val result =
            queryEngine.getUsages(arguments.getFqcn, arguments.getFileInFocus)
          new CallToolResult(createContent(result.show(projectPath)), false)
        }
      },
    )
  }

  private def createFindDepTool(): AsyncToolSpecification = {
    createAsyncTool[FindDepArgs](
      "find-dep",
      """|Find a dependency using coursier, optionally specify organization, name, and version.
         |It will try to return completions for the dependency string.
         |At a minimum you should specify the dependency organization. When only organization is
         |specified, it will return all organizations with the specified prefix. If name is additionally
         |specified, it will return all names with the specified prefix in the organization. If version is additionally
         |specified, it will return all versions with the specified prefix in the organization and name.
         |""".stripMargin,
      arguments => {
        val org = Some(arguments.organization).map(_.trim).filter(_.nonEmpty)
        val name = arguments.name.map(_.trim).filter(_.nonEmpty)
        val version = arguments.version.map(_.trim).filter(_.nonEmpty)
        val scalaVersion = arguments.getFileInFocusOpt match {
          case Some(path) => scalaVersionSelector.scalaVersionForPath(path)
          case None => scalaVersionSelector.fallbackScalaVersion()
        }
        val coursierComplete = new CoursierComplete(scalaVersion)
        val scalaBinaryVersion =
          ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)

        /* Some logic to handle different possible agent queries
         */
        val potentialDepStrings = (org, name, version) match {
          case (Some(org), Some(name), Some(versionStr)) =>
            val versionQuery =
              if (versionStr.contains("latest")) "" else versionStr
            List(
              FindDepKey.version -> s"$org:${name}_$scalaBinaryVersion:$versionQuery",
              FindDepKey.version -> s"$org:$name:$versionQuery",
            )
          case (Some(org), Some(name), None) =>
            List(
              FindDepKey.version -> s"$org:${name}_$scalaBinaryVersion:",
              FindDepKey.version -> s"$org:$name:",
              FindDepKey.name -> s"$org:$name",
            )
          case (Some(org), None, _) =>
            List(
              FindDepKey.name -> s"$org:",
              FindDepKey.organization -> s"$org",
            )
          case _ => List()
        }
        val completions = {
          for {
            (key, depString) <- potentialDepStrings.iterator
            completed = coursierComplete.complete(depString)
            if completed.nonEmpty
          } yield {
            val completedOrLast =
              if (
                key == FindDepKey.version && version
                  .exists(_.contains("latest"))
              )
                completed.headOption.toSeq
              else completed
            McpMessages.FindDep.dependencyReturnMessage(
              key,
              completedOrLast.distinct,
            )
          }
        }.headOption
          .getOrElse(McpMessages.FindDep.noCompletionsFound)

        Future.successful(
          new CallToolResult(createContent(completions), false)
        )
      },
    )
  }

  private def createListModulesTool(): AsyncToolSpecification = {
    createAsyncTool[ListModulesArgs](
      "list-modules",
      "Return the list of modules (build targets) available in the project.",
      (_) =>
        Future {
          val modules =
            buildTargets.allBuildTargetIds.flatMap(
              buildTargets.jvmTarget(_).map(_.displayName)
            )
          new CallToolResult(
            s"Available modules (build targets):${modules.map(module => s"\n- $module").mkString}",
            false,
          )
        },
    )
  }

  private def createDebugMainTool(): AsyncToolSpecification = {
    createAsyncTool[DebugMainArgs](
      "debug-main",
      "Start a debug session for a main class",
      arguments => {
        val args = arguments.args.getOrElse(Nil)
        val env = arguments.env.getOrElse(Map.empty)
        val initialBreakpoints = arguments.initialBreakpoints
          .getOrElse(Nil)
          .flatMap(bp =>
            bp.breakpoints.map(b =>
              Map[String, Object](
                "source" -> bp.source,
                "line" -> Integer.valueOf(b.line),
                "condition" -> b.condition.orNull,
                "logMessage" -> b.logMessage.orNull,
              ).filter(_._2 != null)
            )
          )

        val params = DebugDiscoveryParams(
          runType = "run",
          path = null,
          mainClass = arguments.mainClass,
          buildTarget = arguments.module.orNull,
          args = args.asJava,
          jvmOptions = null,
          env = env.asJava,
          envFile = null,
        )

        val logic = for {
          debugParams <- debugDiscovery.debugDiscovery(params)
          session <- debugProvider.start(debugParams)
          _ <- scheduleDebuggerInitialization(
            session,
            isAttach = false,
            initialBreakpoints,
          )
        } yield {
          val content = s"""|{
                            | "status": "success",
                            | "message": "Debug session started successfully",
                            | "sessionName": "${session.sessionName}",
                            | "sessionId": "${session.id}",
                            | "debugUri": "${session.uri}"
                            |}""".stripMargin
          new CallToolResult(createContent(content), false)
        }
        logic
      },
    )
  }

  private def createDebugTestTool(): AsyncToolSpecification = {
    createAsyncTool[DebugTestArgs](
      "debug-test",
      "Start a debug session for a test suite",
      arguments => {
        val initialBreakpoints = arguments.initialBreakpoints
          .getOrElse(Nil)
          .flatMap(bp =>
            bp.breakpoints.map(b =>
              Map[String, Object](
                "source" -> bp.source,
                "line" -> Integer.valueOf(b.line),
                "condition" -> b.condition.orNull,
                "logMessage" -> b.logMessage.orNull,
              ).filter(_._2 != null)
            )
          )

        val params = DebugDiscoveryParams(
          runType = "testTarget",
          path = null,
          mainClass = arguments.testClass,
          buildTarget = arguments.module.orNull,
          args = arguments.testMethod.map(m => List(s"*$m*").asJava).orNull,
          jvmOptions = null,
          env = null,
          envFile = null,
        )

        val logic = for {
          debugParams <- debugDiscovery.debugDiscovery(params)
          session <- debugProvider.start(debugParams)
          _ <- scheduleDebuggerInitialization(
            session,
            isAttach = false,
            initialBreakpoints,
          )
        } yield {
          val content = s"""|{
                            | "status": "success",
                            | "message": "Debug session started successfully",
                            | "sessionName": "${session.sessionName}",
                            | "sessionId": "${session.id}",
                            | "debugUri": "${session.uri}"
                            |}""".stripMargin
          new CallToolResult(createContent(content), false)
        }
        logic
      },
    )
  }

  private def createDebugAttachTool(): AsyncToolSpecification = {
    createAsyncTool[DebugAttachArgs](
      "debug-attach",
      "Attach to a remote JVM debug port",
      arguments => {
        val hostName = arguments.hostName.getOrElse("localhost")
        val initialBreakpoints = arguments.initialBreakpoints
          .getOrElse(Nil)
          .flatMap(bp =>
            bp.breakpoints.map(b =>
              Map[String, Object](
                "source" -> bp.source,
                "line" -> Integer.valueOf(b.line),
                "condition" -> b.condition.orNull,
                "logMessage" -> b.logMessage.orNull,
              ).filter(_._2 != null)
            )
          )

        // Find build target if module is specified, or use first available target
        val buildTargetOpt = arguments.module
          .flatMap { m =>
            val found = buildTargets.findByDisplayName(m)
            found
          }
          .map(_.getId())
          .orElse {
            val first = buildTargets.allBuildTargetIds.headOption
            first
          }

        buildTargetOpt match {
          case Some(buildTarget) =>
            val debugParams = new DebugSessionParams(JList.of(buildTarget))
            debugParams.setDataKind("scala-attach-remote")

            // Create the attach params similar to how ServerCommands.StartAttach works
            val attachData = Map(
              "hostName" -> hostName,
              "port" -> arguments.port,
            )
            debugParams.setData(attachData.asJava.toJson)

            debugProvider
              .start(debugParams)
              .map { session =>
                // Schedule debugger initialization to handle suspend=y
                scheduleDebuggerInitialization(
                  session,
                  isAttach = false,
                  initialBreakpoints,
                )

                val content =
                  s"""|{
                      | "status": "success",
                      | "message": "Attached to remote debugger successfully",
                      | "host": "$hostName:${arguments.port}",
                      | "sessionName": "${session.sessionName}",
                      | "sessionId": "${session.id}",
                      | "debugUri": "${session.uri}"
                      |}""".stripMargin
                new CallToolResult(createContent(content), false)
              }
          case None =>
            scribe.error(
              s"No build target found for module: ${arguments.module.getOrElse("<none>")}"
            )
            Future
              .successful(
                new CallToolResult(
                  createContent(
                    s"Error: Module not found: ${arguments.module.getOrElse("<no module specified>")}. Please specify a valid module for source mapping."
                  ),
                  true,
                )
              )
        }
      },
    )
  }

  private def createDebugSessionsTool(): AsyncToolSpecification = {
    createAsyncTool[DebugSessionsArgs](
      "debug-sessions",
      "List active debug sessions",
      arguments => {
        Future {
          val activeSessions = debugProvider.allDebugSessions()

          if (arguments.includeHistorical.getOrElse(false)) {
            // Include both active and historical sessions
            val allSessionIds = debugOutputManager.listSessions()

            val content = if (allSessionIds.isEmpty) {
              """{
                |  "status": "success",
                |  "message": "No debug sessions found",
                |  "sessions": []
                |}""".stripMargin
            } else {
              val sessionInfo = allSessionIds
                .map { sessionId =>
                  val activeSession = activeSessions.find(_.id == sessionId)
                  activeSession match {
                    case Some(session) =>
                      s"""|{
                          | "sessionName": "${session.sessionName}",
                          | "sessionId": "${session.id}",
                          | "debugUri": "${session.uri}",
                          | "status": "active"
                          |}""".stripMargin
                    case None =>
                      s"""|{
                          | "sessionId": "$sessionId",
                          | "status": "historical"
                          |}""".stripMargin
                  }
                }
                .mkString(",\n")

              s"""{
                 |  "status": "success",
                 |  "message": "All debug sessions (active and historical)",
                 |  "sessions": [
                 |$sessionInfo
                 |]
                 |}""".stripMargin
            }
            new CallToolResult(createContent(content), false)
          } else {
            // Only active sessions (original behavior)
            val content = activeSessions
              .map { session =>
                s"""|{
                    | "sessionName": "${session.sessionName}",
                    | "sessionId": "${session.id}",
                    | "debugUri": "${session.uri}",
                    | "status": "active"
                    |}""".stripMargin
              }
              .mkString(
                """{
                  |  "status": "success",
                  |  "message": "Active debug sessions",
                  |  "sessions": [""".stripMargin,
                ",\n",
                "]\n}",
              )
            new CallToolResult(createContent(content), false)
          }
        }
      },
    )
  }

  private def createDebugPauseTool(): AsyncToolSpecification =
    createAsyncTool[DebugPauseArgs](
      name = "debug-pause",
      description = "Pause execution in a debug session",
      handler = args =>
        withSession(args.sessionId) { debugger =>
          debugger
            .pause(args.threadId)
            .map { _ =>
              new CallToolResult(createContent("Execution paused"), false)
            }
        },
    )

  private def createDebugContinueTool(): AsyncToolSpecification =
    createAsyncTool[DebugContinueArgs](
      name = "debug-continue",
      description = "Continue execution in a debug session",
      handler = args =>
        withSession(args.sessionId) { debugger =>
          debugger
            .step(args.threadId.getOrElse(0), DebugStep.Continue)
            .map { _ =>
              new CallToolResult(createContent("Execution continued"), false)
            }
        },
    )

  private def createDebugStepTool(): AsyncToolSpecification =
    createAsyncTool[DebugStepArgs](
      name = "debug-step",
      description = "Step execution in a debug session",
      handler = args =>
        withSession(args.sessionId) { debugger =>
          val stepTypeEnum = args.stepType match {
            case StepType.in => DebugStep.StepIn
            case StepType.out => DebugStep.StepOut
            case StepType.over => DebugStep.StepOver
          }

          debugger
            .step(args.threadId, stepTypeEnum)
            .map { _ =>
              new CallToolResult(
                createContent(s"Step ${args.stepType} completed"),
                false,
              )
            }
        },
    )

  private def createDebugEvaluateTool(): AsyncToolSpecification =
    createAsyncTool[DebugEvaluateArgs](
      "debug-evaluate",
      "Evaluate an expression in the debug context",
      args =>
        withSession(args.sessionId) { debugger =>
          val evaluationResultPromise = Promise[EvaluateResponse]()
          debugger
            .step(
              args.frameId,
              DebugStep.Evaluate(
                args.expression,
                args.frameId,
                { result =>
                  evaluationResultPromise.success(result)
                },
                DebugStep.Continue,
              ),
            )
          evaluationResultPromise.future
            .map { result =>
              val content =
                s"""|Result: ${result.getResult}
                    |Type: ${Option(result.getType).getOrElse("unknown")}
                    |Variables Reference: ${result.getVariablesReference}""".stripMargin
              new CallToolResult(createContent(content), false)
            }
        },
    )

  private def createDebugVariablesTool(): AsyncToolSpecification =
    createAsyncTool[DebugVariablesArgs](
      "debug-variables",
      "Get variables in the current debug context",
      args =>
        withSession(args.sessionId) { debugger =>
          // First get the stack trace to find the requested frame
          debugger
            .stackTrace(args.threadId)
            .flatMap { stackTrace =>
              if (stackTrace.getStackFrames.isEmpty) {
                Future.failed(new Exception("No stack frames available"))
              } else {
                // Get scopes for the frame
                val frameId = stackTrace.getStackFrames.head.getId
                debugger
                  .scopes(frameId)
                  .flatMap { scopes =>
                    // Get variables for all scopes
                    Future
                      .sequence(scopes.getScopes.toSeq.map { scope =>
                        debugger
                          .variables(scope.getVariablesReference)
                          .map(vars => (scope.getName, vars))
                      })
                      .map { variablesByScope =>
                        val content = variablesByScope
                          .map { case (scopeName, vars) =>
                            s"""|$scopeName:
                                |${vars.getVariables
                                 .map { v =>
                                   s"  ${v.getName}: ${v.getValue} (${Option(v.getType).getOrElse("?")})"
                                 }
                                 .mkString("\n")}""".stripMargin
                          }
                          .mkString("\n\n")
                        new CallToolResult(createContent(content), false)
                      }
                  }
              }
            }
        },
    )

  private def createDebugBreakpointsTool(): AsyncToolSpecification =
    createAsyncTool[DebugBreakpointsArgs](
      "debug-breakpoints",
      "Set breakpoints in a debug session",
      args =>
        withSession(args.sessionId) { debugger =>
          val breakpoints = args.breakpoints.breakpoints.map { bp =>
            val b = new org.eclipse.lsp4j.debug.SourceBreakpoint()
            b.setLine(bp.line)
            b.setCondition(bp.condition.orNull)
            b.setLogMessage(bp.logMessage.orNull)
            b

          }.toArray

          val source = args.breakpoints.source
          val dapSource = new org.eclipse.lsp4j.debug.Source()
          // Ensure paths are absolute - resolve relative paths against project path
          val resolvedPath =
            if (
              source.startsWith("file:") || source.startsWith("jar:") || source
                .startsWith("/")
            ) {
              source
            } else {
              // It's a relative path - resolve against project path
              projectPath.resolve(source).toURI.toString
            }
          dapSource.setPath(resolvedPath)

          debugger
            .setBreakpoints(dapSource, breakpoints)
            .map { response =>
              val verifiedBreakpoints = response.getBreakpoints
              val content = if (verifiedBreakpoints.isEmpty) {
                "All breakpoints cleared"
              } else {
                verifiedBreakpoints
                  .map { bp =>
                    s"""|Line ${bp.getLine}: ${if (bp.isVerified) "verified" else "not verified"}
                        |${Option(bp.getMessage).map(msg => s"  Message: $msg").getOrElse("")}""".stripMargin
                  }
                  .mkString("\n")
              }
              new CallToolResult(createContent(content), false)
            }
        },
    )

  private def createDebugTerminateTool(): AsyncToolSpecification =
    createAsyncTool[DebugTerminateArgs](
      "debug-terminate",
      "Terminate a debug session",
      args => {
        debugProvider
          .terminate(args.sessionId)
          .map { _ =>
            // Mark session as completed in the output manager
            debugOutputManager.completeSession(args.sessionId)

            new CallToolResult(
              createContent("Debug session terminated"),
              false,
            )
          }
      },
    )

  private def createDebugThreadsTool(): AsyncToolSpecification =
    createAsyncTool[DebugThreadsArgs](
      "debug-threads",
      "List all threads in a debug session",
      args =>
        withSession(args.sessionId) { debugger =>
          debugger
            .threads()
            .map { threadsResponse =>
              val threads = threadsResponse.getThreads
                .map { thread =>
                  // Ensure thread exists in state manager
                  debugProvider.debugStateManager
                    .onThreadStarted(args.sessionId, thread.getId)
                  val threadState = debugProvider.debugStateManager
                    .getThreadState(args.sessionId, thread.getId)
                    .map(_.stateString)
                    .getOrElse("unknown")
                  s"Thread ${thread.getId}: ${thread.getName} - $threadState"
                }
                .mkString("\n")
              new CallToolResult(createContent(threads), false)
            }
        },
    )

  private def createDebugStackTraceTool(): AsyncToolSpecification =
    createAsyncTool[DebugStackTraceArgs](
      "debug-stack-trace",
      "Get stack trace for a thread",
      args => {
        withSession(args.sessionId) { debugger =>
          debugger
            .stackTrace(args.threadId)
            .map { stackTraceResponse =>
              val stackTrace = stackTraceResponse.getStackFrames
                .map { frame =>
                  val location = Option(frame.getSource)
                    .map(source => s"${source.getPath}:${frame.getLine}")
                    .getOrElse("<unknown>")
                  s"  #${frame.getId} ${frame.getName} at $location"
                }
                .mkString("\n")
              new CallToolResult(
                createContent(
                  s"Stack trace for thread ${args.threadId}:\n$stackTrace"
                ),
                false,
              )
            }
        }
      },
    )

  private def createDebugOutputTool(): AsyncToolSpecification =
    createAsyncTool[DebugOutputArgs](
      "debug-output",
      "Get output from a debug session",
      args => {
        Future {
          // Get outputs from debugOutputManager (works for both active and historical sessions)
          val outputs = debugOutputManager.getOutputs(args.sessionId)

          if (outputs.isEmpty) {
            new CallToolResult(
              createContent(
                s"No output found for debug session: ${args.sessionId}"
              ),
              false,
            )
          } else {
            // Filter by output type
            val typeFilteredOutputs = args.outputType.getOrElse("all") match {
              case "stdout" => outputs.filter(_.getCategory == "stdout")
              case "stderr" => outputs.filter(_.getCategory == "stderr")
              case _ => outputs // "all" - return everything
            }

            // Apply regex filter if provided
            val filteredOutputs = args.regex match {
              case Some(pattern) =>
                try {
                  val regex = pattern.r
                  typeFilteredOutputs.filter { output =>
                    val text = Option(output.getOutput).getOrElse("")
                    regex.findFirstIn(text).isDefined
                  }
                } catch {
                  case e: java.util.regex.PatternSyntaxException =>
                    throw new IllegalArgumentException(
                      s"Invalid regex pattern: ${e.getMessage}"
                    )
                }
              case None => typeFilteredOutputs
            }

            // Take only the last maxLines entries
            val limitedOutputs =
              filteredOutputs.takeRight(args.maxLines.getOrElse(100))

            val content = if (limitedOutputs.isEmpty) {
              "No output matches the filters."
            } else {
              limitedOutputs
                .map { output =>
                  val category = Option(output.getCategory).getOrElse("unknown")
                  val text = Option(output.getOutput).getOrElse("")
                  s"[$category] $text"
                }
                .mkString("")
            }

            new CallToolResult(createContent(content), false)
          }
        }
      },
    )

  private def sessionNotFound: Future[CallToolResult] = {
    Future
      .successful(
        new CallToolResult(createContent("Session not found"), true)
      )
  }

  private def withSession(sessionId: String)(
      handler: Debugger => Future[CallToolResult]
  ): Future[CallToolResult] =
    debugProvider.debugger(sessionId).map(handler).getOrElse(sessionNotFound)

  private def createMetalsLogsResource(): AsyncResourceSpecification = {
    val resource = new Resource(
      "metals://logs/metals.log", "Metals Server Logs",
      "The main log file for the Metals language server", "text/plain",
      null, // annotations parameter
    )

    new AsyncResourceSpecification(
      resource,
      new BiFunction[
        McpAsyncServerExchange,
        io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest,
        Mono[ReadResourceResult],
      ] {
        override def apply(
            exchange: McpAsyncServerExchange,
            request: io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest,
        ): Mono[ReadResourceResult] = {
          Future {
            val logFile = projectPath.resolve(".metals/metals.log")
            val content = if (logFile.exists) {
              try {
                logFile.readText
              } catch {
                case NonFatal(e) => s"Error reading log file: ${e.getMessage}"
              }
            } else {
              // Create a dummy log entry for testing purposes
              "INFO: Metals log file not yet created. The log will be available once logging starts."
            }

            val resourceContents = new TextResourceContents(
              "metals://logs/metals.log",
              "text/plain",
              content,
            )
            new ReadResourceResult(Arrays.asList(resourceContents))
          }.toMono
        }
      },
    )
  }

  private def createDebugOutputResource(
      suffix: String = ""
  ): AsyncResourceSpecification = {
    val resource = new Resource(
      s"metals://debug/{sessionId}/output${suffix}",
      "Debug Session Output",
      "Output from a debug session (stdout/stderr)",
      "text/plain",
      null, // annotations parameter
    )

    new AsyncResourceSpecification(
      resource,
      new BiFunction[
        McpAsyncServerExchange,
        io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest,
        Mono[ReadResourceResult],
      ] {
        override def apply(
            exchange: McpAsyncServerExchange,
            request: io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest,
        ): Mono[ReadResourceResult] = {
          Future {
            val uri = request.uri

            // Parse the sessionId from the URI path: metals://debug/{sessionId}/output
            // Handle URIs with and without query parameters
            val baseUri = uri.split("\\?").head
            val sessionIdPattern = """metals://debug/([^/]+)/output""".r
            val sessionId = baseUri match {
              case sessionIdPattern(id) => id
              case _ =>
                throw new IllegalArgumentException(s"Invalid URI format: $uri")
            }

            // Parse query parameters from the URI
            val queryParams = Option(new java.net.URI(uri).getQuery)
              .map(_.split("&"))
              .map(_.map(_.split("=", 2)))
              .map(_.collect { case Array(k, v) =>
                (k, java.net.URLDecoder.decode(v, "UTF-8"))
              }.toMap)
              .getOrElse(Map.empty)

            val maxLines = queryParams
              .get("maxLines")
              .flatMap(s => Try(s.toInt).toOption)
              .getOrElse(100)
            val outputType = queryParams.getOrElse("outputType", "all")
            val regexPattern = queryParams.get("regex")

            // Get outputs from debugOutputManager (works for both active and historical sessions)
            val outputs = debugOutputManager.getOutputs(sessionId)

            val content = if (outputs.isEmpty) {
              s"No output found for debug session: $sessionId"
            } else {
              // Filter by output type
              val typeFilteredOutputs = outputType match {
                case "stdout" => outputs.filter(_.getCategory == "stdout")
                case "stderr" => outputs.filter(_.getCategory == "stderr")
                case _ => outputs // "all" - return everything
              }

              // Apply regex filter if provided
              val filteredOutputs = regexPattern match {
                case Some(pattern) =>
                  try {
                    val regex = pattern.r
                    typeFilteredOutputs.filter { output =>
                      val text = Option(output.getOutput).getOrElse("")
                      regex.findFirstIn(text).isDefined
                    }
                  } catch {
                    case e: java.util.regex.PatternSyntaxException =>
                      throw new IllegalArgumentException(
                        s"Invalid regex pattern: ${e.getMessage}"
                      )
                  }
                case None => typeFilteredOutputs
              }

              // Take only the last maxLines entries
              val limitedOutputs = filteredOutputs.takeRight(maxLines)

              if (limitedOutputs.isEmpty) {
                "No output matches the filters."
              } else {
                limitedOutputs
                  .map { output =>
                    val category =
                      Option(output.getCategory).getOrElse("unknown")
                    val text = Option(output.getOutput).getOrElse("")
                    s"[$category] $text"
                  }
                  .mkString("")
              }
            }

            val resourceContents = new TextResourceContents(
              uri,
              "text/plain",
              content,
            )
            new ReadResourceResult(Arrays.asList(resourceContents))
          }.recover { case e: Exception =>
            val errorContents = new TextResourceContents(
              request.uri,
              "text/plain",
              s"Error: ${e.getMessage}",
            )
            new ReadResourceResult(Arrays.asList(errorContents))
          }.toMono
        }
      },
    )
  }

  private def withErrorHandling(
      f: (McpAsyncServerExchange, JMap[String, Object]) => Mono[CallToolResult]
  ): BiFunction[
    McpAsyncServerExchange,
    JMap[String, Object],
    Mono[CallToolResult],
  ] = { (exchange, arguments) =>
    try {
      f(exchange, arguments)
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Error while processing request: ${e.getMessage}, arguments: ${arguments.toJson}, stacktrace:" +
            e.getStackTrace.mkString("\n")
        )
        Mono.just(
          new CallToolResult(
            createContent(
              s"Error: ${e.getMessage}, arguments: ${arguments.toJson}"
            ),
            true,
          )
        )
    }
  }

  implicit class XtensionFuture[T](val f: Future[T]) {
    def toMono: Mono[T] = Mono.fromFuture(f.asJava)
  }

  sealed trait HasOptionalFileInFocus { self =>
    def fileInFocus: Option[String]

    def getFileInFocusOpt: Option[AbsolutePath] =
      fileInFocus
        .filter(_.nonEmpty)
        .map(path => AbsolutePath(Path.of(path))(projectPath))
        .orElse { focusedDocument() }
  }

  sealed trait HasFileInFocus extends HasOptionalFileInFocus { self =>
    def getFileInFocus: AbsolutePath = getFileInFocusOpt
      .getOrElse(throw MissingFileInFocusException)
  }

  sealed trait HasFqcn { self =>
    def fqcn: String
    def getFqcn: String = fqcn.stripPrefix("_empty_.")
  }

  case class ImportBuildArgs()
  implicit val importBuildArgsDecoder: Decoder[ImportBuildArgs] = deriveDecoder
  implicit val importBuildArgsSchema: json.Schema[ImportBuildArgs] =
    json.Schema.`object`[ImportBuildArgs](Nil)

  case class CompileFullArgs()
  implicit val compileFullArgsDecoder: Decoder[CompileFullArgs] = deriveDecoder
  implicit val compileFullArgsSchema: json.Schema[CompileFullArgs] =
    json.Schema.`object`[CompileFullArgs](Nil)

  case class CompileFileArgs(
      @description(
        "The file to compile, if empty we will try to detect file in focus. Will return errors in the file focused file, if any. If no errors in the file, it will return errors in the module the file belongs to, if any."
      )
      fileInFocus: Option[String]
  ) extends HasFileInFocus
  implicit val compileFileArgsDecoder: Decoder[CompileFileArgs] = deriveDecoder
  implicit val compileFileArgsSchema: json.Schema[CompileFileArgs] =
    json.Json.schema[CompileFileArgs]

  case class CompileModuleArgs(
      @description("The module's (build target's) name to compile")
      module: String
  )
  implicit val compileModuleArgsDecoder: Decoder[CompileModuleArgs] =
    deriveDecoder
  implicit val compileModuleArgsSchema: json.Schema[CompileModuleArgs] =
    json.Json.schema[CompileModuleArgs]

  case class TestArgs(
      @description(
        "The file containing the test suite, if empty we will try to detect it"
      )
      testFile: Option[String],
      @description("Fully qualified name of the test class to run")
      testClass: String,
      @description(
        "Print all output from the test suite, otherwise prints only errors and summary"
      )
      verbose: Option[Boolean] = None,
  )
  implicit val testArgsDecoder: Decoder[TestArgs] = deriveDecoder
  implicit val testArgsSchema: json.Schema[TestArgs] =
    json.Json.schema[TestArgs]

  case class GlobSearchArgs(
      @description("Substring of the symbol to search for")
      query: String,
      @description(
        "The current file in focus for context, if empty we will try to detect it"
      )
      fileInFocus: Option[String],
  ) extends HasFileInFocus
  implicit val globSearchArgsDecoder: Decoder[GlobSearchArgs] = deriveDecoder
  implicit val globSearchArgsSchema: json.Schema[GlobSearchArgs] =
    json.Json.schema[GlobSearchArgs]

  case class TypedGlobSearchArgs(
      @description("Substring of the symbol to search for")
      query: String,
      @description("The type of symbol to search for")
      symbolType: List[String],
      @description(
        "The current file in focus for context, if empty we will try to detect it"
      )
      fileInFocus: Option[String],
  ) extends HasFileInFocus
  implicit val typedGlobSearchArgsDecoder: Decoder[TypedGlobSearchArgs] =
    deriveDecoder
  implicit val typedGlobSearchArgsSchema: json.Schema[TypedGlobSearchArgs] =
    json.Json.schema[TypedGlobSearchArgs]

  case class InspectArgs(
      @description("Fully qualified name of the symbol to inspect")
      fqcn: String,
      @description(
        "The current file in focus for context, if empty we will try to detect it"
      )
      fileInFocus: Option[String],
  ) extends HasFqcn
      with HasFileInFocus
  implicit val inspectArgsDecoder: Decoder[InspectArgs] = deriveDecoder
  implicit val inspectArgsSchema: json.Schema[InspectArgs] =
    json.Json.schema[InspectArgs]

  case class GetDocsArgs(
      @description(
        "Fully qualified name of the symbol to get documentation for"
      )
      fqcn: String
  ) extends HasFqcn
  implicit val getDocsArgsDecoder: Decoder[GetDocsArgs] = deriveDecoder
  implicit val getDocsArgsSchema: json.Schema[GetDocsArgs] =
    json.Json.schema[GetDocsArgs]

  case class GetUsagesArgs(
      @description("Fully qualified name of the symbol to get usages for")
      fqcn: String,
      @description(
        "The current file in focus for context, if empty we will try to detect it"
      )
      fileInFocus: Option[String],
  ) extends HasFqcn
      with HasFileInFocus
  implicit val getUsagesArgsDecoder: Decoder[GetUsagesArgs] = deriveDecoder
  implicit val getUsagesArgsSchema: json.Schema[GetUsagesArgs] =
    json.Json.schema[GetUsagesArgs]

  object FindDepKey {
    val version = "version"
    val name = "name"
    val organization = "organization"
  }
  case class FindDepArgs(
      @description(
        "Organization to search for or its prefix when name and version are not specified. for example 'org.scalamet'"
      )
      organization: String,
      @description(
        "Dependency name to search for or its prefix when version is not specified, for example 'scalameta_2.13' or 'scalam'. Needs organization to be specified."
      )
      name: Option[String],
      @description(
        "Version to search for or its prefix, for example '0.1.0' or '0.1'. Needs name and organization to be specified."
      )
      version: Option[String],
      @description(
        "The current file in focus for context, if empty we will try to detect it"
      )
      fileInFocus: Option[String],
  ) extends HasOptionalFileInFocus
  implicit val findDepArgsDecoder: Decoder[FindDepArgs] = deriveDecoder
  implicit val findDepArgsSchema: json.Schema[FindDepArgs] =
    json.Json.schema[FindDepArgs]

  case class ListModulesArgs()
  implicit val listModulesArgsDecoder: Decoder[ListModulesArgs] = deriveDecoder
  implicit val listModulesArgsSchema: json.Schema[ListModulesArgs] =
    json.Schema.`object`[ListModulesArgs](Nil)

  case class DebugMainArgs(
      @description("Fully qualified name of the main class to debug")
      mainClass: String,
      @description("The module (build target) containing the main class")
      module: Option[String],
      @description("Command line arguments to pass to the main class")
      args: Option[List[String]],
      @description("Environment variables to set for the debug session")
      env: Option[Map[String, String]],
      @description("Breakpoints to set before starting execution")
      initialBreakpoints: Option[List[BreakpointsInFile]],
  )
  implicit val debugMainArgsDecoder: Decoder[DebugMainArgs] = deriveDecoder
  implicit val debugMainArgsSchema: json.Schema[DebugMainArgs] =
    json.Json.schema[DebugMainArgs]

  case class DebugTestArgs(
      @description("Fully qualified name of the test class to debug")
      testClass: String,
      @description("Specific test method to debug (optional)")
      testMethod: Option[String],
      @description("The module (build target) containing the test class")
      module: Option[String],
      @description("Breakpoints to set before starting execution")
      initialBreakpoints: Option[List[BreakpointsInFile]],
  )
  implicit val debugTestArgsDecoder: Decoder[DebugTestArgs] = deriveDecoder
  implicit val debugTestArgsSchema: json.Schema[DebugTestArgs] =
    json.Json.schema[DebugTestArgs]

  case class DebugSessionsArgs(
      @description(
        "Include historical (terminated) debug sessions in addition to active ones"
      )
      includeHistorical: Option[Boolean]
  )
  implicit val debugSessionsArgsDecoder: Decoder[DebugSessionsArgs] =
    deriveDecoder
  implicit val debugSessionsArgsSchema: json.Schema[DebugSessionsArgs] =
    json.Json.schema[DebugSessionsArgs]

  case class DebugPauseArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description(
        "Optional thread ID to pause. If not specified, all threads are paused"
      )
      threadId: Option[Int],
  )
  implicit val debugPauseArgsDecoder: Decoder[DebugPauseArgs] = deriveDecoder
  implicit val debugPauseArgsSchema: json.Schema[DebugPauseArgs] =
    json.Json.schema[DebugPauseArgs]

  case class DebugContinueArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description(
        "Optional thread ID to continue. If not specified, all threads continue"
      )
      threadId: Option[Int],
  )
  implicit val debugContinueArgsDecoder: Decoder[DebugContinueArgs] =
    deriveDecoder
  implicit val debugContinueArgsSchema: json.Schema[DebugContinueArgs] =
    json.Json.schema[DebugContinueArgs]

  sealed trait StepType

  object StepType {
    case object in extends StepType
    case object out extends StepType
    case object over extends StepType
  }
  implicit val stepTypeDecoder: Decoder[StepType] = deriveDecoder
  implicit val stepTypeSchema: json.Schema[StepType] =
    json.Json.schema[StepType]

  case class DebugStepArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description("The thread ID to step")
      threadId: Int,
      @description("The type of step operation")
      stepType: StepType,
  )
  implicit val debugStepArgsDecoder: Decoder[DebugStepArgs] = deriveDecoder
  implicit val debugStepArgsSchema: json.Schema[DebugStepArgs] =
    json.Json.schema[DebugStepArgs]

  case class DebugEvaluateArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description("The expression to evaluate")
      expression: String,
      @description("Stack frame ID for evaluation context")
      frameId: Int,
  )
  implicit val debugEvaluateArgsDecoder: Decoder[DebugEvaluateArgs] =
    deriveDecoder
  implicit val debugEvaluateArgsSchema: json.Schema[DebugEvaluateArgs] =
    json.Json.schema[DebugEvaluateArgs]

  case class DebugVariablesArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description("The thread ID to get variables for")
      threadId: Int,
      @description("Optional frame ID. If not specified, uses the top frame")
      frameId: Option[Int],
  )
  implicit val debugVariablesArgsDecoder: Decoder[DebugVariablesArgs] =
    deriveDecoder
  implicit val debugVariablesArgsSchema: json.Schema[DebugVariablesArgs] =
    json.Json.schema[DebugVariablesArgs]

  case class DebugBreakpointsArgs(
      @description("The ID of the debug session")
      sessionId: String,
      breakpoints: BreakpointsInFile,
  )
  implicit val debugBreakpointsArgsDecoder: Decoder[DebugBreakpointsArgs] =
    deriveDecoder
  implicit val debugBreakpointsArgsSchema: json.Schema[DebugBreakpointsArgs] =
    json.Json.schema[DebugBreakpointsArgs]

  case class DebugTerminateArgs(
      @description("The ID of the debug session")
      sessionId: String
  )
  implicit val debugTerminateArgsDecoder: Decoder[DebugTerminateArgs] =
    deriveDecoder
  implicit val debugTerminateArgsSchema: json.Schema[DebugTerminateArgs] =
    json.Json.schema[DebugTerminateArgs]

  case class DebugThreadsArgs(
      @description("The ID of the debug session")
      sessionId: String
  )
  implicit val debugThreadsArgsDecoder: Decoder[DebugThreadsArgs] =
    deriveDecoder
  implicit val debugThreadsArgsSchema: json.Schema[DebugThreadsArgs] =
    json.Json.schema[DebugThreadsArgs]

  case class DebugStackTraceArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description("The thread ID to get stack trace for")
      threadId: Int,
  )
  implicit val debugStackTraceArgsDecoder: Decoder[DebugStackTraceArgs] =
    deriveDecoder
  implicit val debugStackTraceArgsSchema: json.Schema[DebugStackTraceArgs] =
    json.Json.schema[DebugStackTraceArgs]

  sealed trait OutputType
  object OutputType {
    case object all extends OutputType
    case object stdout extends OutputType
    case object stderr extends OutputType
  }
  implicit val outputTypeDecoder: Decoder[OutputType] = deriveDecoder
  implicit val outputTypeSchema: json.Schema[OutputType] =
    json.Json.schema[OutputType]

  case class DebugOutputArgs(
      @description("The ID of the debug session")
      sessionId: String,
      @description("Maximum number of output lines to return (default: 100)")
      maxLines: Option[Int],
      @description("Type of output to retrieve (default: all)")
      outputType: Option[OutputType],
      @description("Optional regex pattern to filter output lines")
      regex: Option[String],
  )
  implicit val debugOutputArgsDecoder: Decoder[DebugOutputArgs] = deriveDecoder
  implicit val debugOutputArgsSchema: json.Schema[DebugOutputArgs] =
    json.Json.schema[DebugOutputArgs]
}

object MetalsMcpServer {

  case class Breakpoint(
      @description("Line number")
      line: Int,
      @description("Optional breakpoint condition")
      condition: Option[String],
      @description("Optional log message")
      logMessage: Option[String],
  )
  implicit val breakpointDecoder: Decoder[Breakpoint] = deriveDecoder
  implicit val breakpointSchema: json.Schema[Breakpoint] =
    json.Json.schema[Breakpoint]

  case class BreakpointsInFile(
      @description(
        "The source file URI, either absolute (`file:///path/to/file.scala`) or JAR URI (`jar:file://path/to/file.jar!/path/to/class.scala`)"
      )
      source: String,
      @description(
        "List of breakpoints to set. Empty array clears all breakpoints"
      )
      breakpoints: List[Breakpoint],
  )
  implicit val breakpointsInFileDecoder: Decoder[BreakpointsInFile] =
    deriveDecoder
  implicit val breakpointsInFileSchema: json.Schema[BreakpointsInFile] =
    json.Json.schema[BreakpointsInFile]

  case class DebugAttachArgs(
      @description("The debug port to attach to")
      port: Int = 5005,
      @description("The hostname of the remote JVM (defaults to localhost)")
      hostName: Option[String],
      @description("The module (build target) for source mapping")
      module: Option[String],
      @description("Breakpoints to set before starting execution")
      initialBreakpoints: Option[List[BreakpointsInFile]],
  )

  implicit val debugAttachArgsDecoder: Decoder[DebugAttachArgs] = deriveDecoder
  implicit val debugAttachArgsSchema: json.Schema[DebugAttachArgs] =
    json.Json.schema[DebugAttachArgs]
}

object McpMessages {

  object FindDep {
    def dependencyReturnMessage(
        key: String,
        completed: Seq[String],
    ): String = {
      s"""|Tool managed to complete `$key` field and got potential values to use for it: ${completed.mkString(", ")}
          |""".stripMargin
    }

    def noCompletionsFound: String =
      "No completions found"
  }
}
sealed trait IncorrectArgumentException extends Exception
class MissingArgumentException(key: String)
    extends Exception(s"Missing argument: $key")
    with IncorrectArgumentException

class IncorrectArgumentTypeException(key: String, expected: String)
    extends Exception(s"Incorrect argument type for $key, expected: $expected")
    with IncorrectArgumentException

object MissingFileInFocusException
    extends Exception(s"Missing fileInFocus and failed to infer it.")
    with IncorrectArgumentException
