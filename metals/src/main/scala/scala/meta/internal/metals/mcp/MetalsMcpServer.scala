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
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.StatusCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Content
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.InstanceHandle
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import reactor.core.publisher.Mono

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
    formattingProvider: FormattingProvider,
    scalafixLlmRuleProvider: ScalafixLlmRuleProvider,
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
      .logging() // Enable logging support (enabled by default with logging level INFO)
      .build();

    // Create server with configuration
    val asyncServer = McpServer
      .async(servlet)
      .serverInfo("scala-mcp-server", "0.1.0")
      .capabilities(capabilities)
      .build()

    cancelable.add(() => asyncServer.close())

    // Register tools
    asyncServer.addTool(createFileCompileTool()).subscribe()
    asyncServer.addTool(createCompileModuleTool()).subscribe()
    asyncServer.addTool(createCompileTool()).subscribe()
    asyncServer.addTool(createTestTool()).subscribe()
    asyncServer.addTool(createGlobSearchTool()).subscribe()
    asyncServer.addTool(createTypedGlobSearchTool()).subscribe()
    asyncServer.addTool(createInspectTool()).subscribe()
    asyncServer.addTool(createGetDocsTool()).subscribe()
    asyncServer.addTool(createGetUsagesTool()).subscribe()
    asyncServer.addTool(importBuildTool()).subscribe()
    asyncServer.addTool(createFindDepTool()).subscribe()
    asyncServer.addTool(createListModulesTool()).subscribe()
    asyncServer.addTool(createFormatTool()).subscribe()
    asyncServer.addTool(createGenerateScalafixRuleTool()).subscribe()
    asyncServer.addTool(createRunScalafixRuleTool()).subscribe()
    asyncServer.addTool(createListScalafixRulesTool()).subscribe()

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
            val allDiagnostics = diagnostics.allDiagnostics
            val diagnosticsOutput = allDiagnostics.show(projectPath)
            val content =
              if (diagnosticsOutput.isEmpty) {
                "Compilation successful."
              } else if (allDiagnostics.hasErrors) {
                s"Compilation failed with errors:\n$diagnosticsOutput"
              } else {
                s"Compilation successful with warnings:\n$diagnosticsOutput"
              }
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
                  val fileDiagnostics = diagnostics.forFile(path)
                  val diagnosticsOutput = fileDiagnostics.show()
                  if (diagnosticsOutput.isEmpty) None
                  else {
                    val prefix = if (fileDiagnostics.hasErrors) {
                      "Found errors in"
                    } else {
                      "Found warnings in"
                    }
                    Some(s"$prefix $path:\n$diagnosticsOutput")
                  }
                }

                def inModuleErrors =
                  for {
                    bt <- buildTarget
                    diagnosticsOutput <- this.inModuleErrors(bt)
                  } yield {
                    val moduleDiagnostics =
                      diagnostics.allDiagnostics.filter { case (path, _) =>
                        buildTargets.inverseSources(path).contains(bt)
                      }
                    val issuesType = if (moduleDiagnostics.hasErrors) {
                      "errors"
                    } else {
                      "warnings"
                    }
                    s"No issues in the file, but found compile $issuesType in the module:\n$diagnosticsOutput"
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
                .map { diagnosticsOutput =>
                  val moduleDiagnostics =
                    diagnostics.allDiagnostics.filter { case (path, _) =>
                      buildTargets.inverseSources(path).contains(target.id)
                    }
                  val prefix = if (moduleDiagnostics.hasErrors) {
                    "Found errors in the module"
                  } else {
                    "Found warnings in the module"
                  }
                  s"$prefix:\n$diagnosticsOutput"
                }
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
    val moduleDiagnostics =
      diagnostics.allDiagnostics.filter { case (path, _) =>
        buildTargets.inverseSources(path).contains(buildTarget)
      }

    if (
      moduleDiagnostics.nonEmpty && (moduleDiagnostics.hasErrors || moduleDiagnostics.hasWarnings)
    ) {
      Some(moduleDiagnostics.show(projectPath))
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
         |    "testName": {
         |      "type": "string",
         |      "description": "Name of the specific test to run within the test class, if empty runs all tests in the class"
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
      new Tool(
        "test",
        """|Run Scala test suite. Execute specific test classes and individual test methods. 
           |Supports verbose output and can run tests from any testing 
           |framework (ScalaTest, MUnit, etc.)""".stripMargin,
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val testClass = arguments.getAs[String]("testClass")
        val optPath = arguments
          .getOptAs[String]("testFile")
          .map(path => AbsolutePath(Path.of(path))(projectPath))
        val testName = arguments.getOptAs[String]("testName")
        val printOnlyErrorsAndSummary = arguments
          .getOptAs[Boolean]("verbose")
          .getOrElse(false)
        val result = mcpTestRunner.runTests(
          testClass,
          optPath,
          testName,
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
      new Tool(
        "glob-search",
        """|Search for symbols using glob pattern. Find packages, classes, objects, methods, traits, 
           |and other Scala symbols by partial name matching. Returns symbol locations 
           |and signatures from the entire project workspace.
           |Use this if you encounter unknown API, for example proprietary libraries.""".stripMargin,
        schema,
      ),
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
        """|Search for symbols by type using glob pattern. Filter symbol search results 
           |by specific symbol types (package, class, object, function, method, trait). 
           |More precise than glob-search when you know the symbol type you're looking for.
           |Use this if you encounter unknown API, for example proprietary libraries.""".stripMargin,
        schema,
      ),
      withErrorHandling { (exchange, arguments) =>
        val query = arguments.getAs[String]("query")
        val path = arguments.getFileInFocus
        val symbolTypes = arguments.getAsList[String]("symbolType")

        val invalidSymbols =
          symbolTypes.filterNot(s => SymbolType.values.exists(_.name == s))
        if (invalidSymbols.nonEmpty) {
          val validTypes = SymbolType.values.map(_.name).mkString(", ")
          throw new InvalidSymbolTypeException(invalidSymbols.toSeq, validTypes)
        }

        val symbolTypesSet =
          symbolTypes.flatMap(s => SymbolType.values.find(_.name == s)).toSet

        queryEngine
          .globSearch(query, symbolTypesSet, path)
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
        """|Get documentation for a chosen Scala symbol. Retrieves ScalaDoc comments, 
           |parameter descriptions, return types, and usage examples for classes, methods, 
           |functions, and other symbols using their fully qualified name.""".stripMargin,
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
        """|Get usages for a chosen Scala symbol. Find all references and usages of classes, 
           |methods, variables, and other symbols across the entire project. Returns precise 
           |locations with file paths and line numbers for refactoring and code analysis.""".stripMargin,
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

  private def createFormatTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "fileInFocus": {
         |      "type": "string",
         |      "description": "The file to format, if empty we will try to detect file in focus"
         |    }
         |  }
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "format-file",
        "Format a Scala file and return the formatted text",
        schema,
      ),
      withErrorHandling { (_, arguments) =>
        val path = arguments.getFileInFocus
        if (path.exists && path.isScalaFilename) {
          val cancelChecker = new org.eclipse.lsp4j.jsonrpc.CancelChecker {
            override def isCanceled(): Boolean = false
            override def checkCanceled(): Unit = ()
          }

          formattingProvider
            .formatForMcp(path, projectPath, cancelChecker)
            .map {
              case Left(errorMessage) =>
                new CallToolResult(
                  createContent(errorMessage),
                  true,
                )
              case Right(None) =>
                new CallToolResult(
                  createContent("File is already properly formatted."),
                  false,
                )
              case Right(Some(formattedText)) =>
                new CallToolResult(
                  createContent(formattedText),
                  false,
                )
            }
            .toMono
        } else {
          Future
            .successful(
              new CallToolResult(
                createContent(
                  s"Error: File not found or not a Scala file: $path"
                ),
                true,
              )
            )
            .toMono
        }
      },
    )
  }

  private def createGenerateScalafixRuleTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": {
        |    "ruleName": {
        |      "type": "string",
        |      "description": "The name of the scalafix rule to run, should be a valid scalafix rule name and not include any special characters or whitespaces"
        |    },
        |    "ruleImplementation": {
        |      "type": "string",
        |      "description": "The implementation of the scalafix rule to run, this should contain the actual scalafix rule implementation."
        |    },
        |    "description": {
        |      "type": "string",
        |      "description": "The description of the scalafix rule to run for later MCP invocations."
        |    },
        |    "targets": {
        |      "type": "array",
        |      "description": "The targets to run the rule on, if empty will run on the last focused target"
        |    },
        |    "sampleCode": {
        |      "type": "string",
        |      "description": "Sample code that we are trying to match in the rule, if nothing was matched an error will be returned with the structure of this sample."
        |    },
        |    "fileToRunOn": {
        |      "type": "string",
        |      "description": "File to run it all, if empty will run on all files in given targets"
        |    }
        |  },
        |  "required": ["ruleName", "ruleImplementation"]
        |} 
        |""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "generate-scalafix-rule",
        """|Generate a scalafix rule and run it on the current project.
           |
           |Use this tool whenever you want to migrate a particular code pattern inside the entire codebase. 
           |This might include fixing code smells, refactorings or migrating between versions of Scala or a particular library. 
           |The generated rule will be created in .metals/rules directory and can be later invoked using the `run-scalafix-rule` tool.
           |When a rule with the same name already exists, it will be overwritten. This is useful if you want to update 
           |the rule implementation.
           |""".stripMargin,
        schema,
      ),
      withErrorHandling { (_, arguments) =>
        import scala.meta._
        val ruleName = arguments.getAs[String]("ruleName")
        val ruleImplementation = arguments.getAs[String]("ruleImplementation")
        val description =
          arguments.getOptAs[String]("description").getOrElse(ruleName)
        val sampleCode = arguments.getOptAs[String]("sampleCode")
        def helper = {
          sampleCode match {
            case Some(value) =>
              value.parse[Stat] match {
                case Parsed.Success(value) => value.structure
                case Parsed.Error(_, _, _) =>
                  "Provide a valid sample code to get a better error message"
              }
            case None => "Provide a sample code to get a better error message"
          }
        }
        def errorMessage(exception: String) = {
          s"Error: ${exception}\nSample code structure: ${helper}"
        }
        val runOn = arguments.getPathOpt("fileToRunOn")
        val modules =
          arguments
            .getOptAs[JList[String]]("targets")
            .map(_.asScala.toList)
            .getOrElse(Nil) match {
            case Nil =>
              val targetFile = runOn.orElse(focusedDocument())
              targetFile
                .flatMap(
                  buildTargets
                    .inverseSources(_)
                    .flatMap(buildTargets.scalaTarget(_))
                    .map(_.displayName)
                )
                .toList
            case targets => targets
          }
        val resultingFuture =
          scalafixLlmRuleProvider.runOnAllTargets(
            ruleName,
            ruleImplementation,
            description,
            modules,
            runOn.toList,
          )
        resultingFuture.map {
          case Right(_) =>
            new CallToolResult(
              createContent(
                s"Created and ran Scalafix rule $ruleName successfully"
              ),
              false,
            )
          case Left(error) =>
            new CallToolResult(
              createContent(errorMessage(error)),
              true,
            )
        }.toMono
      },
    )
  }

  private def createRunScalafixRuleTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": {
        |    "ruleName": {
        |      "type": "string",
        |      "description": "The name of the scalafix rule to run. Should be one of the rules from the list-scalafix-rules tool output."
        |    },
        |    "fileToRunOn": {
        |      "type": "string",
        |      "description": "File to run it all, if empty will run on all files"
        |    }
        |  },
        |  "required": ["ruleName"]
        |} 
        |""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "run-scalafix-rule",
        "Run a specific previously existing Scalafix rule (from curated rules or previously created rules) on the focused file or all files",
        schema,
      ),
      withErrorHandling { (_, arguments) =>
        val ruleName = arguments.getAs[String]("ruleName")
        val path = arguments.getPathOpt("fileToRunOn")
        val runResult = path match {
          case Some(path) =>
            scalafixLlmRuleProvider.runScalafixRule(ruleName, path)
          case None =>
            scalafixLlmRuleProvider.runScalafixRuleForAllTargets(ruleName)
        }
        runResult
          .map { _ =>
            new CallToolResult(
              createContent("Scalafix rule run successfully"),
              false,
            )
          }
          .recover { case error =>
            new CallToolResult(createContent(error.getMessage), true)
          }
          .toMono
      },
    )
  }

  private def createListScalafixRulesTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": { }
        |} 
        |""".stripMargin
    new AsyncToolSpecification(
      new Tool(
        "list-scalafix-rules",
        "List currently available scalafix rules from .metals/rules directory. They were previously created by the `run-scalafix-rule` tool.",
        schema,
      ),
      withErrorHandling { (_, _) =>
        Future {
          val allRules = scalafixLlmRuleProvider.allRules
          val content =
            allRules.toList.sortBy(_._1).map { case (ruleName, description) =>
              s"- $ruleName: $description"
            }
          new CallToolResult(
            createContent(
              s"Available scalafix rules:\n${content.mkString("\n")}"
            ),
            false,
          )
        }.toMono
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

    def getAsList[T](key: String)(implicit ct: ClassTag[T]): List[T] =
      try {
        arguments.get(key) match {
          case null => throw new MissingArgumentException(key)
          case value: String =>
            objectMapper
              .readValue(value, classOf[JList[T]])
              .asScala
              .toList
          case value: JList[_] =>
            value.asScala.collect { case value: T => value }.toList
        }
      } catch {
        case _: MissingArgumentException =>
          throw new MissingArgumentException(key)
        case _: Exception =>
          throw new IncorrectArgumentTypeException(
            key,
            s"Array[${ct.runtimeClass.getSimpleName}]",
          )
      }

    /**
     * Like getOptAs, but returns None if the value is an empty string (after trimming).
     */
    def getOptNoEmptyString(key: String): Option[String] =
      getOptAs[String](key).map(_.trim).filter(_.nonEmpty)

    def getPathOpt(key: String): Option[AbsolutePath] =
      getOptAs[String](key)
        .filter(_.nonEmpty)
        .map(path => AbsolutePath(Path.of(path))(projectPath))

    def getFileInFocusOpt: Option[AbsolutePath] =
      getPathOpt("fileInFocus")
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

class InvalidSymbolTypeException(invalid: Seq[String], validTypes: String)
    extends Exception(
      s"Invalid symbol types: ${invalid.mkString(", ")}. Valid types are: $validTypes"
    )
    with IncorrectArgumentException

object MissingFileInFocusException
    extends Exception(s"Missing fileInFocus and failed to infer it.")
    with IncorrectArgumentException
