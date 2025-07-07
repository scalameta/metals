package scala.meta.internal.metals.mcp

import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Arrays
import java.util.function.BiFunction
import java.util.{List => JList}
import java.util.{Map => JMap}

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
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.debug.DebugDiscovery
import scala.meta.internal.metals.debug.DebugOutputManager
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.debug.DebugServer
import scala.meta.internal.metals.mcp.McpPrinter._
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
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource
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
import scala.meta.internal.metals.debug.DebugStep
import scala.concurrent.Promise
import org.eclipse.lsp4j.debug.EvaluateResponse

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

  private def importBuildTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    new AsyncToolSpecification(
      new Tool(
        "import-build",
        "Import the build to IDE. Should be performed after any build changes, e.g. adding dependencies or any changes in build.sbt.",
        schema,
      ),
      withErrorHandling { (exchange, _) =>
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
          }
          .toMono
      },
    )

  }

  private def createCompileTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    new AsyncToolSpecification(
      new Tool("compile-full", "Compile the whole Scala project", schema),
      withErrorHandling { (exchange, _) =>
        compilations
          .cascadeCompile(buildTargets.allBuildTargetIds)
          .map { _ =>
            val errors = diagnostics.allDiagnostics.show(projectPath)
            val content =
              if (errors.isEmpty) "Compilation successful."
              else s"Compilation failed with errors:\n$errors"
            new CallToolResult(createContent(content), false)
          }
          .toMono
      },
    )
  }

  private def createFileCompileTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "fileInFocus": {
         |      "type": "string",
         |      "description": "The file to compile, if empty we will try to detect file in focus. Will return errors in the file focused file, if any. If no errors in the file, it will return errors in the module the file belongs to, if any."
         |    }
         |  }
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("compile-file", "Compile a chosen Scala file", schema),
      withErrorHandling { (exchange, arguments) =>
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
            .toMono
        } else {
          Future
            .successful(
              new CallToolResult(
                createContent(s"Error: File not found: $path"),
                true,
              )
            )
            .toMono
        }
      },
    )
  }

  private def createCompileModuleTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "module": {
         |      "type": "string",
         |      "description": "The module's (build target's) name to compile"
         |    }
         |  },
         |  "required": ["module"]
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("compile-module", "Compile a chosen Scala module", schema),
      withErrorHandling { (exchange, arguments) =>
        val module = arguments.getAs[String]("module")
        Future {
          (buildTargets.allScala ++ buildTargets.allJava).find(
            _.displayName == module
          ) match {
            case Some(target) =>
              val result = inModuleErrors(target.id)
                .map(errors => s"Found errors in the module:\n$errors")
                .orElse(upstreamModulesErros(target.id, "module"))
                .getOrElse("Compilation successful.")
              new CallToolResult(createContent(result), false)
            case None =>
              new CallToolResult(
                createContent(s"Error: Module not found: $module"),
                true,
              )
          }
        }.toMono
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
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "testFile": {
         |      "type": "string",
         |      "description": "The file containing the test suite, if empty we will try to detect it"
         |    },
         |    "testClass": {
         |      "type": "string",
         |      "description": "Fully qualified name of the test class to run"
         |    },
         |    "verbose": {
         |      "type": "boolean",
         |      "description": "Print all output from the test suite, otherwise prints only errors and summary",
         |      "default": false
         |    }
         |  },
         |  "required": ["testClass"]
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("test", "Run Scala test suite", schema),
      withErrorHandling { (exchange, arguments) =>
        val testClass = arguments.getAs[String]("testClass")
        val optPath = arguments
          .getOptAs[String]("testFile")
          .map(path => AbsolutePath(Path.of(path))(projectPath))
        val printOnlyErrorsAndSummary = arguments
          .getOptAs[Boolean]("verbose")
          .getOrElse(false)
        val result = mcpTestRunner.runTests(
          testClass,
          optPath,
          printOnlyErrorsAndSummary,
        )
        (result match {
          case Right(value) =>
            value.map(content =>
              new CallToolResult(createContent(content), false)
            )
          case Left(error) =>
            Future.successful(
              new CallToolResult(createContent(s"Error: $error"), true)
            )
        }).toMono
      },
    )
  }

  private def createGlobSearchTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "Substring of the symbol to search for"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context, if empty we will try to detect it"
          }
        },
        "required": ["query"]
      }
    """
    new AsyncToolSpecification(
      new Tool("glob-search", "Search for symbols using glob pattern", schema),
      withErrorHandling { (exchange, arguments) =>
        val query = arguments.getAs[String]("query")
        val path = arguments.getFileInFocus
        queryEngine
          .globSearch(query, Set.empty, path)
          .map(result =>
            new CallToolResult(
              createContent(result.map(_.show).mkString("\n")),
              false,
            )
          )
          .toMono
      },
    )
  }

  private def createTypedGlobSearchTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "Substring of the symbol to search for"
          },
          "symbolType": {
            "type": "array",
            "items": {
              "type": "string",
              "enum": ["package", "class", "object", "function", "method", "trait"]
            },
            "description": "The type of symbol to search for"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context, if empty we will try to detect it"
          }
        },
        "required": ["query", "symbolType"]
      }
    """
    new AsyncToolSpecification(
      new Tool(
        "typed-glob-search",
        "Search for symbols by type using glob pattern",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val query = arguments.getAs[String]("query")
        val path = arguments.getFileInFocus
        val symbolTypes = scala.jdk.CollectionConverters
          .ListHasAsScala(
            arguments
              .getAs[JList[String]]("symbolType")
          )
          .asScala
          .flatMap(s => SymbolType.values.find(_.name == s))
          .toSet

        queryEngine
          .globSearch(query, symbolTypes, path)
          .map(result =>
            new CallToolResult(
              createContent(result.map(_.show).mkString("\n")),
              false,
            )
          )
          .toMono
      },
    )
  }

  private def createInspectTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "description": "Fully qualified name of the symbol to inspect"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context, if empty we will try to detect it"
          }
        },
        "required": ["fqcn"]
      }
    """
    new AsyncToolSpecification(
      new Tool(
        "inspect",
        """|Inspect a chosen Scala symbol.
           |For packages, objects and traits returns list of members.
           |For classes returns list of members and constructors.
           |For methods returns signatures of all overloaded methods.""".stripMargin,
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        val path = arguments.getFileInFocus
        queryEngine
          .inspect(fqcn, path)
          .map(result =>
            new CallToolResult(
              createContent(result.show),
              false,
            )
          )
          .toMono
      },
    )
  }

  private def createGetDocsTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "description": "Fully qualified name of the symbol to get documentation for"
          }
        },
        "required": ["fqcn"]
      }
    """
    new AsyncToolSpecification(
      new Tool(
        "get-docs",
        "Get documentation for a chosen Scala symbol",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        Future {
          queryEngine.getDocumentation(fqcn) match {
            case Some(result) =>
              new CallToolResult(createContent(result.show), false)
            case None =>
              new CallToolResult(
                createContent("Error: Symbol not found"),
                false,
              )
          }
        }.toMono
      },
    )
  }

  private def createGetUsagesTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "description": "Fully qualified name of the symbol to get usages for"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context, if empty we will try to detect it"
          }
        },
        "required": ["fqcn"]
      }
    """
    new AsyncToolSpecification(
      new Tool(
        "get-usages",
        "Get usages for a chosen Scala symbol. Returns list of files with line numbers.",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        val path = arguments.getFileInFocus
        Future {
          val result = queryEngine.getUsages(fqcn, path)
          new CallToolResult(createContent(result.show(projectPath)), false)
        }.toMono
      },
    )
  }

  object FindDepKey {
    val version = "version"
    val name = "name"
    val organization = "organization"
  }

  private def createFindDepTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": {
        |    "organization": {
        |      "type": "string",
        |      "description": "Organization to search for or its prefix when name and version are not specified. for example 'org.scalamet'"
        |    },
        |    "name": {
        |      "type": "string",
        |      "description": "Dependency name to search for or its prefix when version is not specified, for example 'scalameta_2.13' or 'scalam'. Needs organization to be specified."
        |    },
        |    "version": {
        |      "type": "string",
        |      "description": "Version to search for or its prefix, for example '0.1.0' or '0.1'. Needs name and organization to be specified."
        |    },
        |    "fileInFocus": {
        |      "type": "string",
        |      "description": "The current file in focus for context, if empty we will try to detect it"
        |    }
        |  },
        |  "required": ["organization"]
        |}
        |""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "find-dep",
        """|Find a dependency using coursier, optionally specify organization, name, and version.
           |It will try to return completions for the dependency string.
           |At a minimum you should specify the dependency organization. When only organization is
           |specified, it will return all organizations with the specified prefix. If name is additionally
           |specified, it will return all names with the specified prefix in the organization. If version is additionally
           |specified, it will return all versions with the specified prefix in the organization and name.
           |""".stripMargin,
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val org = arguments.getOptNoEmptyString(FindDepKey.organization)
        val name = arguments.getOptNoEmptyString(FindDepKey.name)
        val version = arguments.getOptNoEmptyString(FindDepKey.version)
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
          case (Some(org), Some(name), Some(version)) =>
            val versionQuery = if (version.contains("latest")) "" else version
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
              if (key == FindDepKey.version && version.contains("latest"))
                completed.headOption.toSeq
              else completed
            McpMessages.FindDep.dependencyReturnMessage(
              key,
              completedOrLast.distinct,
            )
          }
        }.headOption
          .getOrElse(McpMessages.FindDep.noCompletionsFound)

        Future
          .successful(
            new CallToolResult(createContent(completions), false)
          )
          .toMono
      },
    )
  }

  private def createListModulesTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": { }
        |}
        |""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "list-modules",
        "Return the list of modules (build targets) available in the project.",
        schema,
      ),
      withErrorHandling { (_, _) =>
        Future {
          val modules =
            buildTargets.allBuildTargetIds.flatMap(
              buildTargets.jvmTarget(_).map(_.displayName)
            )
          new CallToolResult(
            s"Available modules (build targets):${modules.map(module => s"\n- $module").mkString}",
            false,
          )
        }.toMono
      },
    )
  }

  private def createDebugMainTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "mainClass": {
         |      "type": "string",
         |      "description": "Fully qualified name of the main class to debug"
         |    },
         |    "module": {
         |      "type": "string",
         |      "description": "The module (build target) containing the main class"
         |    },
         |    "args": {
         |      "type": "array",
         |      "items": { "type": "string" },
         |      "description": "Command line arguments to pass to the main class"
         |    },
         |    "env": {
         |      "type": "object",
         |      "description": "Environment variables to set for the debug session"
         |    },
         |    "initialBreakpoints": {
         |      "type": "array",
         |      "items": {
         |        "type": "object",
         |        "properties": {
         |          "source": {
         |            "type": "string",
         |            "description": "Source file path as URI"
         |          },
         |          "line": { "type": "integer", "description": "Line number" },
         |          "condition": { "type": "string", "description": "Optional breakpoint condition" },
         |          "logMessage": { "type": "string", "description": "Optional log message" }
         |        },
         |        "required": ["line"]
         |      },
         |      "description": "Breakpoints to set before starting execution"
         |    }
         |  },
         |  "required": ["mainClass"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-main", "Start a debug session for a main class", schema),
      withErrorHandling { (exchange, arguments) =>
        val mainClass = arguments.getAs[String]("mainClass")
        val module = arguments.getOptAs[String]("module")
        val args = arguments
          .getOptAs[JList[String]]("args")
          .map(_.asScala.toList)
          .getOrElse(Nil)
        val env = arguments
          .getOptAs[JMap[String, String]]("env")
          .map(_.asScala.toMap)
          .getOrElse(Map.empty)

        val initialBreakpoints = arguments
          .getOptAs[JList[JMap[String, Object]]]("initialBreakpoints")
          .map(_.asScala.toList.map(_.asScala.toMap))
          .getOrElse(Nil)

        val params = DebugDiscoveryParams(
          runType = "run",
          path = null,
          mainClass = mainClass,
          buildTarget = module.orNull,
          args = args.asJava,
          jvmOptions = null,
          env = env.asJava,
          envFile = null,
        )

        val logic = for {
          debugParams <- {
            debugDiscovery.debugDiscovery(params)
          }
          session <- {
            debugProvider.start(debugParams)
          }
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

          // Create embedded resource pointing to debug output
          val debugOutputResource = new TextResourceContents(
            s"metals://debug/${session.id}/output",
            "text/plain",
            "", // Empty content - will be populated when accessed via resource
          )
          val embeddedResource =
            new EmbeddedResource(null, null, debugOutputResource)

          new CallToolResult(
            Arrays.asList[Content](
              new TextContent(content)
              // embeddedResource
            ),
            false,
          )
        }
        logic.recover { case e: Exception =>
          new CallToolResult(
            createContent(
              s"""|{
                  | "status": "error",
                  | "message": "Failed to start debug session: ${e.getMessage}:\\n${e.getStackTrace.mkString("\\n")}"
                  |}""".stripMargin
            ),
            true,
          )
        }.toMono
      },
    )
  }

  private def createDebugTestTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "testClass": {
         |      "type": "string",
         |      "description": "Fully qualified name of the test class to debug"
         |    },
         |    "testMethod": {
         |      "type": "string",
         |      "description": "Specific test method to debug (optional)"
         |    },
         |    "module": {
         |      "type": "string",
         |      "description": "The module (build target) containing the test class"
         |    },
         |    "initialBreakpoints": {
         |      "type": "array",
         |      "items": {
         |        "type": "object",
         |        "properties": {
         |          "source": {
         |            "type": "string",
         |            "description": "Source file path as URI"
         |          },
         |          "line": { "type": "integer", "description": "Line number" },
         |          "condition": { "type": "string", "description": "Optional breakpoint condition" },
         |          "logMessage": { "type": "string", "description": "Optional log message" }
         |        },
         |        "required": ["line"]
         |      },
         |      "description": "Breakpoints to set before starting execution"
         |    }
         |  },
         |  "required": ["testClass"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-test", "Start a debug session for a test suite", schema),
      withErrorHandling { (exchange, arguments) =>
        val testClass = arguments.getAs[String]("testClass")
        val testMethod = arguments.getOptAs[String]("testMethod")
        val module = arguments.getOptAs[String]("module")
        val initialBreakpoints = arguments
          .getOptAs[JList[JMap[String, Object]]]("initialBreakpoints")
          .map(_.asScala.toList.map(_.asScala.toMap))
          .getOrElse(Nil)

        val params = DebugDiscoveryParams(
          runType = "testTarget",
          path = null,
          mainClass = testClass,
          buildTarget = module.orNull,
          args = testMethod.map(m => List(s"*$m*").asJava).orNull,
          jvmOptions = null,
          env = null,
          envFile = null,
        )

        debugDiscovery
          .debugDiscovery(params)
          .flatMap { debugParams =>
            debugProvider.start(debugParams)
          }
          .map { session =>
            // Schedule debugger initialization to handle suspend=y
            scheduleDebuggerInitialization(
              session,
              isAttach = false,
              initialBreakpoints,
            )

            val content = s"""|{
                              | "status": "success",
                              | "message": "Debug session started successfully",
                              | "sessionName": "${session.sessionName}",
                              | "sessionId": "${session.id}",
                              | "debugUri": "${session.uri}"
                              |}""".stripMargin
            // Create embedded resource pointing to debug output
            val debugOutputResource = new TextResourceContents(
              s"metals://debug/${session.id}/output",
              "text/plain",
              "", // Empty content - will be populated when accessed via resource
            )
            val embeddedResource =
              new EmbeddedResource(null, null, debugOutputResource)

            new CallToolResult(
              Arrays.asList[Content](
                new TextContent(content)
                // embeddedResource
              ),
              false,
            )
          }
          .recover { case e: Exception =>
            new CallToolResult(
              createContent(
                s"""|{
                    | "status": "error",
                    | "message": "Failed to start debug session: ${e.getMessage}:\\n${e.getStackTrace.mkString("\\n")}"
                    |}""".stripMargin
              ),
              true,
            )
          }
          .toMono
      },
    )
  }

  private def createDebugAttachTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "port": {
         |      "type": "integer",
         |      "description": "The debug port to attach to",
         |      "default": 5005
         |    },
         |    "hostName": {
         |      "type": "string",
         |      "description": "The hostname of the remote JVM (defaults to localhost)"
         |    },
         |    "module": {
         |      "type": "string",
         |      "description": "The module (build target) for source mapping"
         |    },
         |    "initialBreakpoints": {
         |      "type": "array",
         |      "items": {
         |        "type": "object",
         |        "properties": {
         |          "source": {
         |            "type": "string",
         |            "description": "Source file path as URI."
         |          },
         |          "line": { "type": "integer", "description": "Line number" },
         |          "condition": { "type": "string", "description": "Optional breakpoint condition" },
         |          "logMessage": { "type": "string", "description": "Optional log message" }
         |        },
         |        "required": ["line"]
         |      },
         |      "description": "Breakpoints to set before starting execution"
         |    }
         |  },
         |  "required": ["port"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-attach", "Attach to a remote JVM debug port", schema),
      withErrorHandling { (exchange, arguments) =>
        val port = arguments.getAs[java.lang.Integer]("port").intValue()
        val hostName =
          arguments.getOptAs[String]("hostName").getOrElse("localhost")
        val module = arguments.getOptAs[String]("module")
        val initialBreakpoints = arguments
          .getOptAs[JList[JMap[String, Object]]]("initialBreakpoints")
          .map(_.asScala.toList.map(_.asScala.toMap))
          .getOrElse(Nil)

        // Find build target if module is specified, or use first available target
        val buildTargetOpt = module
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
              "port" -> port,
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
                      | "host": "$hostName:$port",
                      | "sessionName": "${session.sessionName}",
                      | "sessionId": "${session.id}",
                      | "debugUri": "${session.uri}"
                      |}""".stripMargin

                // Create embedded resource pointing to debug output
                val debugOutputResource = new TextResourceContents(
                  s"metals://debug/${session.id}/output",
                  "text/plain",
                  "", // Empty content - will be populated when accessed via resource
                )
                val embeddedResource =
                  new EmbeddedResource(null, null, debugOutputResource)

                new CallToolResult(
                  Arrays.asList[Content](
                    new TextContent(content)
                    // embeddedResource
                  ),
                  false,
                )
              }
              .recover { case e: Exception =>
                scribe.error(s"Failed to attach to remote debugger", e)
                new CallToolResult(
                  createContent(
                    s"""|{
                        | "status": "error",
                        | "message": "Failed to attach to remote debugger: ${e.getMessage}"
                        |}""".stripMargin
                  ),
                  true,
                )
              }
              .toMono
          case None =>
            scribe.error(
              s"No build target found for module: ${module.getOrElse("<none>")}"
            )
            Future
              .successful(
                new CallToolResult(
                  createContent(
                    s"Error: Module not found: ${module.getOrElse("<no module specified>")}. Please specify a valid module for source mapping."
                  ),
                  true,
                )
              )
              .toMono
        }
      },
    )
  }

  private def createDebugSessionsTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "includeHistorical": {
         |      "type": "boolean",
         |      "description": "Include historical (terminated) debug sessions in addition to active ones",
         |      "default": false
         |    }
         |  }
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("debug-sessions", "List active debug sessions", schema),
      withErrorHandling { (exchange, arguments) =>
        Future {
          val includeHistorical = arguments
            .getOptAs[java.lang.Boolean]("includeHistorical")
            .map(_.booleanValue())
            .getOrElse(false)
          val activeSessions = debugProvider.allDebugSessions()

          if (includeHistorical) {
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
        }.toMono
      },
    )
  }

  private def createDebugPauseTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "threadId": {
         |      "type": "integer",
         |      "description": "Optional thread ID to pause. If not specified, all threads are paused"
         |    }
         |  },
         |  "required": ["sessionId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-pause", "Pause execution in a debug session", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val threadId =
          arguments.getOptAs[java.lang.Integer]("threadId").map(_.intValue())
        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            debugger
              .pause(threadId)
              .map { _ =>
                new CallToolResult(createContent("Execution paused"), false)
              }
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to pause: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None => sessionNotFound
        }
      },
    )
  }

  private def createDebugContinueTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "threadId": {
         |      "type": "integer",
         |      "description": "Optional thread ID to continue. If not specified, all threads continue"
         |    }
         |  },
         |  "required": ["sessionId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool(
        "debug-continue",
        "Continue execution in a debug session",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val threadId =
          arguments.getOptAs[java.lang.Integer]("threadId").map(_.intValue())

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            debugger
              .step(threadId.getOrElse(0), DebugStep.Continue)
              .map { _ =>
                new CallToolResult(createContent("Execution continued"), false)
              }
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to continue: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugStepTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "threadId": {
         |      "type": "integer",
         |      "description": "The thread ID to step"
         |    },
         |    "stepType": {
         |      "type": "string",
         |      "enum": ["in", "out", "over"],
         |      "description": "The type of step operation"
         |    }
         |  },
         |  "required": ["sessionId", "threadId", "stepType"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-step", "Step execution in a debug session", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val threadId = arguments.getAs[java.lang.Integer]("threadId").intValue()
        val stepType = arguments.getAs[String]("stepType")

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            val stepTypeEnum = stepType match {
              case "in" => DebugStep.StepIn
              case "out" => DebugStep.StepOut
              case "over" => DebugStep.StepOver
            }

            debugger
              .step(threadId, stepTypeEnum)
              .map { _ =>
                new CallToolResult(
                  createContent(s"Step $stepType completed"),
                  false,
                )
              }
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to step: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugEvaluateTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "expression": {
         |      "type": "string",
         |      "description": "The expression to evaluate"
         |    },
         |    "frameId": {
         |      "type": "integer",
         |      "description": "Stack frame ID for evaluation context"
         |    }
         |  },
         |  "required": ["sessionId", "expression", "frameId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool(
        "debug-evaluate",
        "Evaluate an expression in the debug context",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val expression = arguments.getAs[String]("expression")
        val frameId = arguments.getAs[java.lang.Integer]("frameId").intValue()

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            val evaluationResultPromise = Promise[EvaluateResponse]()
            debugger
              .step(
                frameId,
                DebugStep.Evaluate(
                  expression,
                  frameId,
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
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to evaluate: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugVariablesTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "threadId": {
         |      "type": "integer",
         |      "description": "The thread ID to get variables for"
         |    },
         |    "frameId": {
         |      "type": "integer",
         |      "description": "Optional frame ID. If not specified, uses the top frame"
         |    }
         |  },
         |  "required": ["sessionId", "threadId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool(
        "debug-variables",
        "Get variables in the current debug context",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val threadId = arguments.getAs[java.lang.Integer]("threadId").intValue()
        val _ = arguments
          .getOptAs[java.lang.Integer]("frameId")
          .map(_.intValue())
          .getOrElse(
            0
          ) // frameId param currently unused, always using top frame

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            // First get the stack trace to find the requested frame
            debugger
              .stackTrace(threadId)
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
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to get variables: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugBreakpointsTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "source": {
         |      "type": "string",
         |      "description": "The source file URI, either absolute (`file:///path/to/file.scala`) or JAR URI (`jar:file://path/to/file.jar!/path/to/class.scala`)"
         |    },
         |    "breakpoints": {
         |      "type": "array",
         |      "items": {
         |        "type": "object",
         |        "properties": {
         |          "line": { "type": "integer", "description": "Line number" },
         |          "condition": { "type": "string", "description": "Optional condition" },
         |          "logMessage": { "type": "string", "description": "Optional log message" }
         |        },
         |        "required": ["line"]
         |      },
         |      "description": "List of breakpoints to set. Empty array clears all breakpoints"
         |    }
         |  },
         |  "required": ["sessionId", "source", "breakpoints"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool(
        "debug-breakpoints",
        "Set breakpoints in a debug session",
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val source = arguments.getAs[String]("source")
        val breakpointsJson = arguments
          .getAs[java.util.List[java.util.Map[String, Object]]]("breakpoints")

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            val breakpoints = breakpointsJson.asScala.map { bp =>
              val b = new org.eclipse.lsp4j.debug.SourceBreakpoint()
              b.setLine(
                bp.get("line").asInstanceOf[java.lang.Number].intValue()
              )
              b.setCondition(Option(bp.get("condition")).map(_.toString).orNull)
              b.setLogMessage(
                Option(bp.get("logMessage")).map(_.toString).orNull
              )
              b
            }.toArray
            val dapSource = new org.eclipse.lsp4j.debug.Source()
            // Ensure paths are absolute - resolve relative paths against project path
            val resolvedPath =
              if (
                source.startsWith("file:") || source
                  .startsWith("jar:") || source.startsWith("/")
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
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to set breakpoints: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugTerminateTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session to terminate"
         |    }
         |  },
         |  "required": ["sessionId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-terminate", "Terminate a debug session", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")

        debugProvider
          .terminate(sessionId)
          .map { _ =>
            // Mark session as completed in the output manager
            debugOutputManager.completeSession(sessionId)

            new CallToolResult(
              createContent("Debug session terminated"),
              false,
            )
          }
          .recover { case e: Exception =>
            new CallToolResult(
              createContent(s"Failed to terminate: ${e.getMessage}"),
              true,
            )
          }
          .toMono
      },
    )
  }

  private def createDebugThreadsTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    }
         |  },
         |  "required": ["sessionId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-threads", "List all threads in a debug session", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            debugger
              .threads()
              .map { threadsResponse =>
                val threads = threadsResponse.getThreads
                  .map { thread =>
                    // Ensure thread exists in state manager
                    debugProvider.debugStateManager
                      .onThreadStarted(sessionId, thread.getId)
                    val threadState = debugProvider.debugStateManager
                      .getThreadState(sessionId, thread.getId)
                      .map(_.stateString)
                      .getOrElse("unknown")
                    s"Thread ${thread.getId}: ${thread.getName} - $threadState"
                  }
                  .mkString("\n")
                new CallToolResult(createContent(threads), false)
              }
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to get threads: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None =>
            sessionNotFound
        }
      },
    )
  }

  private def createDebugStackTraceTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "threadId": {
         |      "type": "integer",
         |      "description": "The thread ID to get stack trace for"
         |    }
         |  },
         |  "required": ["sessionId", "threadId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-stack-trace", "Get stack trace for a thread", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val threadId = arguments.getAs[Integer]("threadId").intValue()

        debugProvider.debugger(sessionId) match {
          case Some(debugger) =>
            debugger
              .stackTrace(threadId)
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
                    s"Stack trace for thread $threadId:\n$stackTrace"
                  ),
                  false,
                )
              }
              .recover { case e: Exception =>
                new CallToolResult(
                  createContent(s"Failed to get stack trace: ${e.getMessage}"),
                  true,
                )
              }
              .toMono
          case None => sessionNotFound
        }
      },
    )
  }

  private def createDebugOutputTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "sessionId": {
         |      "type": "string",
         |      "description": "The ID of the debug session"
         |    },
         |    "maxLines": {
         |      "type": "integer",
         |      "description": "Maximum number of output lines to return (default: 100)",
         |      "default": 100
         |    },
         |    "outputType": {
         |      "type": "string",
         |      "enum": ["stdout", "stderr", "all"],
         |      "description": "Type of output to retrieve (default: all)",
         |      "default": "all"
         |    },
         |    "regex": {
         |      "type": "string",
         |      "description": "Optional regex pattern to filter output lines"
         |    }
         |  },
         |  "required": ["sessionId"]
         |}""".stripMargin

    new AsyncToolSpecification(
      new Tool("debug-output", "Get output from a debug session", schema),
      withErrorHandling { (exchange, arguments) =>
        val sessionId = arguments.getAs[String]("sessionId")
        val maxLines = arguments
          .getOptAs[java.lang.Integer]("maxLines")
          .map(_.intValue())
          .getOrElse(100)
        val outputType =
          arguments.getOptAs[String]("outputType").getOrElse("all")
        val regexPattern = arguments.getOptAs[String]("regex")

        Future {
          // Get outputs from debugOutputManager (works for both active and historical sessions)
          val outputs = debugOutputManager.getOutputs(sessionId)

          if (outputs.isEmpty) {
            new CallToolResult(
              createContent(s"No output found for debug session: $sessionId"),
              false,
            )
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
        }.recover { case e: Exception =>
          new CallToolResult(
            createContent(s"Failed to get output: ${e.getMessage}"),
            true,
          )
        }.toMono
      },
    )
  }

  private def sessionNotFound: Mono[CallToolResult] = {
    Future
      .successful(
        new CallToolResult(createContent("Session not found"), true)
      )
      .toMono
  }

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

  implicit class XtensionArguments(
      val arguments: java.util.Map[String, Object]
  ) {
    def getFqcn: String =
      getAs[String]("fqcn").stripPrefix("_empty_.")

    def getAs[T](key: String): T =
      arguments.get(key) match {
        case null => throw new MissingArgumentException(key)
        case value =>
          Try(value.asInstanceOf[T]).toOption.getOrElse(
            throw new IncorrectArgumentTypeException(
              key,
              value.getClass.getName,
            )
          )
      }

    def getOptAs[T](key: String): Option[T] =
      arguments.get(key) match {
        case null => None
        case value =>
          Try(value.asInstanceOf[T]).toOption.orElse(
            throw new IncorrectArgumentTypeException(
              key,
              value.getClass.getName,
            )
          )
      }

    /**
     * Like getOptAs, but returns None if the value is an empty string (after trimming).
     */
    def getOptNoEmptyString(key: String): Option[String] =
      getOptAs[String](key).map(_.trim).filter(_.nonEmpty)

    def getFileInFocusOpt: Option[AbsolutePath] =
      getOptAs[String]("fileInFocus")
        .filter(_.nonEmpty)
        .map(path => AbsolutePath(Path.of(path))(projectPath))
        .orElse { focusedDocument() }

    def getFileInFocus: AbsolutePath =
      getFileInFocusOpt
        .getOrElse(throw MissingFileInFocusException)
  }
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
