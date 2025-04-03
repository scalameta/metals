package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.ArrayList
import java.util.Arrays
import java.util.{List => JList}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.QueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.io.AbsolutePath

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
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
)(implicit
    ec: ExecutionContext
) extends Cancelable {

  private val objectMapper = new ObjectMapper()

  private def createContent(text: String): JList[Content] = {
    Arrays.asList(new TextContent(text))
  }

  private def cancelable = new MutableCancelable()

  def run(): Unit = {
    val servlet = new HttpServletSseServerTransportProvider(objectMapper, "/")

    val capabilities = ServerCapabilities
      .builder()
      .tools(true) // Tool support with list changes notifications
      .logging() // Enable logging support (enabled by default with logging level INFO)
      .build();

    // Create server with configuration
    val syncServer = McpServer
      .sync(servlet)
      .serverInfo("scala-mcp-server", "0.1.0")
      .capabilities(capabilities)
      .build()

    cancelable.add(() => syncServer.close())

    // Register tools
    syncServer.addTool(createFileCompileTool())
    syncServer.addTool(createCompileTool())
    syncServer.addTool(createTestTool())
    syncServer.addTool(createGlobSearchTool())
    syncServer.addTool(createTypedGlobSearchTool())
    syncServer.addTool(createInspectTool())
    syncServer.addTool(createGetDocsTool())
    syncServer.addTool(createGetUsagesTool())

    // Log server initialization
    syncServer.loggingNotification(
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

  private def createCompileTool(): SyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    new SyncToolSpecification(
      new Tool("compile-full", "Compile Scala project", schema),
      (exchange, _) => {
        val res =
          compilations.cascadeCompile(buildTargets.allBuildTargetIds).map { _ =>
            val content = diagnostics.allDiagnostics
              .map { case (path, diag) =>
                val startLine = diag.getRange().getStart().getLine()
                val endLine = diag.getRange().getEnd().getLine()
                s"${path.toRelative(projectPath)} ($startLine-$endLine): ${diag.getMessage()}"
              }
              .mkString("\n")
            new CallToolResult(createContent(content), false)
          }
        Await.result(res, 10.seconds)
      },
    )
  }

  private def createFileCompileTool(): SyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |  "properties": {
         |    "fileInFocus": {
         |      "type": "string"
         |    }
         |  }
         |}""".stripMargin
    new SyncToolSpecification(
      new Tool("compile-file", "Compile Scala file", schema),
      (exchange, arguments) => {
        try {
          withPath(arguments) { path =>
            val res = compilations.compileFile(path).map {
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
            Await.result(res, 10.seconds)
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createTestTool(): SyncToolSpecification = {
    val schema =
      """|{
         |  "type": "object",
         |    "properties": {
         |      "fileInFocus": {
         |        "type": "string"
         |      },
         |      "testClass": {
         |        "type": "string"
         |      }
         |    },
         |    "required": ["testClass"]
         |  }
         |}""".stripMargin
    new SyncToolSpecification(
      new Tool("test", "Run Scala tests", schema),
      (exchange, arguments) => {
        try {
          val testClass = arguments.get("testClass").asInstanceOf[String]
          withPath(arguments) { path =>
            val result = mcpTestRunner.runTests(path, testClass)
            result match {
              case Right(value) =>
                val content = Await.result(value, 10.seconds)
                new CallToolResult(createContent(content), false)
              case Left(error) =>
                new CallToolResult(createContent(s"Error: $error"), true)
            }
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createGlobSearchTool(): SyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "query": {
            "type": "string"
          },
          "fileInFocus": {
            "type": "string"
          }
        },
        "required": ["query"]
      }
    """
    new SyncToolSpecification(
      new Tool("glob-search", "Search for symbols using glob pattern", schema),
      (exchange, arguments) => {
        try {
          val query = arguments.get("query").asInstanceOf[String]
          withPath(arguments) { path =>
            val result = queryEngine
              .globSearch(query, Set.empty, path)
              .map(_.show)
              .mkString("\n")
            new CallToolResult(createContent(result), false)
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createTypedGlobSearchTool(): SyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "query": {
            "type": "string"
          },
          "symbolType": {
            "type": "array",
            "items": {
              "type": "string",
              "enum": ["package", "class", "object", "function", "method", "trait"]
            }
          },
          "fileInFocus": {
            "type": "string"
          }
        },
        "required": ["query", "symbolType"]
      }
    """
    new SyncToolSpecification(
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

            val result = queryEngine
              .globSearch(query, symbolTypes, path)
              .map(_.show)
              .mkString("\n")
            new CallToolResult(createContent(result), false)
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createInspectTool(): SyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string"
          },
          "fileInFocus": {
            "type": "string"
          },
          "provideMethodSignatures": {
            "type": "boolean",
            "default": false
          }
        },
        "required": ["fqcn"]
      }
    """
    new SyncToolSpecification(
      new Tool("inspect", "Inspect a fully qualified class name", schema),
      (exchange, arguments) => {
        try {
          val fqcn = arguments.get("fqcn").asInstanceOf[String]
          val provideMethodSignatures =
            arguments.get("provideMethodSignatures").asInstanceOf[Boolean]
          withPath(arguments) { path =>
            val result = queryEngine
              .inspect(fqcn, path, provideMethodSignatures)
              .map(_.show)
            new CallToolResult(
              createContent(Await.result(result, 10.seconds)),
              false,
            )
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createGetDocsTool(): SyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "fileInFocus": {
              "type": "string"
            }
          }
        },
        "required": ["fqcn"]
      }
    """
    new SyncToolSpecification(
      new Tool(
        "get-docs",
        "Get documentation for a fully qualified class name",
        schema,
      ),
      (exchange, arguments) => {
        try {
          val fqcn = arguments.get("fqcn").asInstanceOf[String]
          withPath(arguments) { path =>
            val result = queryEngine.getDocumentation(fqcn, path).map {
              case Some(result) => result.show
              case None => "Error: Symbol not found"
            }
            new CallToolResult(
              createContent(Await.result(result, 10.seconds)),
              false,
            )
          }
        } catch {
          case e: Exception =>
            new CallToolResult(createContent(s"Error: ${e.getMessage}"), true)
        }
      },
    )
  }

  private def createGetUsagesTool(): SyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": { 
            "type": "string"
          },
          "fileInFocus": {
            "type": "string"
          }
        },
        "required": ["fqcn"]
      }
    """
    new SyncToolSpecification(
      new Tool(
        "get-usages",
        "Get usages for a fully qualified class name",
        schema,
      ),
      (exchange, arguments) => {
        val fqcn = arguments.get("fqcn").asInstanceOf[String]
        withPath(arguments) { path =>
          val result =
            queryEngine.getUsages(fqcn, path).map(_.show(projectPath))
          new CallToolResult(
            createContent(Await.result(result, 10.seconds)),
            false,
          )
        }
      },
    )
  }

  private def withPath(
      arguments: java.util.Map[String, Object]
  )(f: AbsolutePath => CallToolResult): CallToolResult = {
    Option(arguments.get("fileInFocus").asInstanceOf[String])
      .map(path => AbsolutePath(Path.of(path))(projectPath))
      .orElse { focusedDocument() } match {
      case Some(value) => f(value)
      case None =>
        new CallToolResult(
          createContent("Error: No file path provided or incorrect file path"),
          true,
        )
    }
  }
}
