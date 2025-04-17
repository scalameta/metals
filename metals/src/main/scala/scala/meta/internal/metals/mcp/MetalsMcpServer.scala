package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.ArrayList
import java.util.Arrays
import java.util.{List => JList}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.QueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.io.AbsolutePath

import com.fasterxml.jackson.databind.ObjectMapper
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
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import reactor.core.publisher.Mono
class MetalsMcpServer(
    queryEngine: QueryEngine,
    projectPath: AbsolutePath,
    compilations: Compilations,
    focusedDocument: () => Option[AbsolutePath],
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    mcpTestRunner: McpTestRunner,
    editorName: String,
    projectName: String,
    languageClient: LanguageClient,
    connectionProvider: ConnectionProvider,
)(implicit
    ec: ExecutionContext
) extends Cancelable {

  private val objectMapper = new ObjectMapper()

  private def createContent(text: String): JList[Content] = {
    Arrays.asList(new TextContent(text))
  }

  private def cancelable = new MutableCancelable()

  private val sseEndpoint = "/sse"

  def run(): Unit = {
    val servlet = new HttpServletSseServerTransportProvider(
      objectMapper,
      "/",
      sseEndpoint,
    ) {
      override def doGet(
          request: HttpServletRequest,
          response: HttpServletResponse,
      ): Unit = {
        super.doGet(request, response)
        val pathInfo = request.getPathInfo();
        if (sseEndpoint.equals(pathInfo)) {
          val scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
          val pingTask = new Runnable {
            override def run(): Unit = {
              try {
                response.getWriter().write("data: {\"type\":\"ping\"}\n\n")
                response.getWriter().flush()
              } catch {
                case _: Exception => scheduler.shutdown()
              }
            }
          }
          scheduler.scheduleAtFixedRate(
            pingTask,
            30,
            30,
            java.util.concurrent.TimeUnit.SECONDS,
          )
        }
      }
    }

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
    asyncServer.addTool(createCompileTool()).subscribe()
    asyncServer.addTool(createTestTool()).subscribe()
    asyncServer.addTool(createGlobSearchTool()).subscribe()
    asyncServer.addTool(createTypedGlobSearchTool()).subscribe()
    asyncServer.addTool(createInspectTool()).subscribe()
    asyncServer.addTool(createGetDocsTool()).subscribe()
    asyncServer.addTool(createGetUsagesTool()).subscribe()
    asyncServer.addTool(importBuildTool()).subscribe()

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

    val configPort = CursorMcpConfig.readPort(projectPath, projectName)
    val undertowServer = Undertow
      .builder()
      .addHttpListener(configPort.getOrElse(0), "localhost")
      .setHandler(deployment.start())
      .build()
    undertowServer.start()

    val listenerInfo = undertowServer.getListenerInfo()
    val port =
      listenerInfo.get(0).getAddress().asInstanceOf[InetSocketAddress].getPort()

    if (editorName == "Cursor" && !configPort.isDefined) {
      CursorMcpConfig.writeConfig(port, projectName, projectPath)
    } else {
      languageClient.showMessage(
        new MessageParams(
          MessageType.Info,
          s"Metals MCP server started on port: $port. Refresh connection if needed.",
        )
      )
    }

    cancelable.add(() => undertowServer.stop())
  }

  override def cancel(): Unit = cancelable.cancel()

  private def importBuildTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    new AsyncToolSpecification(
      new Tool(
        "import-build",
        "Import the build to IDE. Should be performed after any build changes, e.g. adding dependepcies, any changes in build.sbt.",
        schema,
      ),
      (exchange, _) => {
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
          }
          .toMono
      },
    )

  }

  private def createCompileTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    new AsyncToolSpecification(
      new Tool("compile-full", "Compile the whole Scalaproject", schema),
      (exchange, _) => {
        compilations
          .cascadeCompile(buildTargets.allBuildTargetIds)
          .map { _ =>
            val content = diagnostics.allDiagnostics
              .map { case (path, diag) =>
                val startLine = diag.getRange().getStart().getLine()
                val endLine = diag.getRange().getEnd().getLine()
                s"${path.toRelative(projectPath)} ($startLine-$endLine): ${diag.getMessage()}"
              }
              .mkString("\n")
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
         |      "description": "The file to compile, if empty we will try to detect file in focus"
         |    }
         |  }
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("compile-file", "Compile a chosenScala file", schema),
      (exchange, arguments) => {
        try {
          withPath(arguments) { path =>
            compilations.compileFile(path).map {
              case Some(_) =>
                val result = diagnostics
                  .forFile(path)
                  .map { d =>
                    val startLine = d.getRange().getStart().getLine()
                    val endLine = d.getRange().getEnd().getLine()
                    s"($startLine-$endLine):\n${d.getMessage()}"
                  }
                  .mkString("\n")
                new CallToolResult(createContent(result), false)
              case None =>
                new CallToolResult(
                  createContent(
                    s"Error: Incorrect file path: ${path.toString()}"
                  ),
                  true,
                )
            }
          }.toMono
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
      },
    )
  }

  private def createTestTool(): AsyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |    "properties": {
         |      "testFile": {
         |        "type": "string",
         |        "description": "The file containing the test suite, if empty we will try to detect it"
         |      },
         |      "testClass": {
         |        "type": "string",
         |        "description": "Fully qualified name of the test class to run"
         |      },
         |      "verbose": {
         |        "type": "boolean",
         |        "description": "Print all output from the test suite, otherwise prints only errors and summary",
         |        "default": false
         |      }
         |    },
         |    "required": ["testClass"]
         |  }
         |}""".stripMargin
    new AsyncToolSpecification(
      new Tool("test", "Run Scala test suite", schema),
      (exchange, arguments) => {
        try {
          val testClass = arguments.get("testClass").asInstanceOf[String]
          val optPath = Option(arguments.get("testFile"))
            .map(_.asInstanceOf[String])
            .filter(_.nonEmpty)
            .map(path => AbsolutePath(Path.of(path))(projectPath))
          val printOnlyErrorsAndSummary =
            arguments.get("verbose").asInstanceOf[Boolean]
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
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
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
      (exchange, arguments) => {
        try {
          val query = arguments.get("query").asInstanceOf[String]
          withPath(arguments) { path =>
            queryEngine
              .globSearch(query, Set.empty, path)
              .map(result =>
                new CallToolResult(
                  createContent(result.map(_.show).mkString("\n")),
                  false,
                )
              )
          }.toMono
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
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
      (exchange, arguments) => {
        try {
          val query = arguments.get("query").asInstanceOf[String]
          withPath(arguments) { path =>
            val symbolTypes = arguments
              .get("symbolType")
              .asInstanceOf[ArrayList[String]]
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
          }.toMono
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
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
          },
          "provideMethodSignatures": {
            "type": "boolean",
            "default": false,
            "description": "Whether to provide method members with signatures (when set to true) or names only (when set to false)"
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
      (exchange, arguments) => {
        try {
          val fqcn = arguments.getFqcn
          val provideMethodSignatures =
            arguments.get("provideMethodSignatures").asInstanceOf[Boolean]
          withPath(arguments) { path =>
            queryEngine
              .inspect(fqcn, path, provideMethodSignatures)
              .map(result =>
                new CallToolResult(
                  createContent(result.show),
                  false,
                )
              )
          }.toMono
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
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
      (exchange, arguments) => {
        try {
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
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
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
      (exchange, arguments) => {
        try {
          val fqcn = arguments.getFqcn
          withPath(arguments) { path =>
            Future {
              val result = queryEngine.getUsages(fqcn, path)
              new CallToolResult(createContent(result.show(projectPath)), false)
            }
          }.toMono
        } catch {
          case e: Exception =>
            Mono.just(
              new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
            )
        }
      },
    )
  }

  private def withPath(
      arguments: java.util.Map[String, Object]
  )(f: AbsolutePath => Future[CallToolResult]): Future[CallToolResult] = {
    Option(arguments.get("fileInFocus"))
      .map(_.asInstanceOf[String])
      .filter(_.nonEmpty)
      .map(path => AbsolutePath(Path.of(path))(projectPath))
      .orElse { focusedDocument() } match {
      case Some(value) => f(value)
      case None =>
        Future.successful(
          new CallToolResult(
            createContent(
              "Error: No file path provided or incorrect file path"
            ),
            true,
          )
        )
    }
  }

  implicit class XtensionFuture[T](val f: Future[T]) {
    def toMono: Mono[T] = Mono.fromFuture(f.asJava)
  }

  implicit class XtensionArguments(val arguments: java.util.Map[String, Object]) {
    def getFqcn: String = arguments.get("fqcn").asInstanceOf[String].stripPrefix("_empty_/")
    
  }


}
