package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.ArrayList
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
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.io.AbsolutePath

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
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
    editorName: String,
    projectName: String,
    languageClient: LanguageClient,
    connectionProvider: ConnectionProvider,
    scalaVersionSelector: ScalaVersionSelector,
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
          // Scheduling a ping task to keep the connection alive,
          // see: https://github.com/AltimateAI/vscode-dbt-power-user/pull/1631 or
          // https://github.com/modelcontextprotocol/rust-sdk/pull/74
          val pingTask = new Runnable {
            override def run(): Unit = {
              try {
                response.getWriter().write(": ping\n\n")
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
    asyncServer.addTool(createFindDepTool()).subscribe()

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

    val editor =
      Editor.allEditors.find(_.names.contains(editorName)).getOrElse(NoEditor)
    val configPort = McpConfig.readPort(projectPath, projectName, editor)
    val undertowServer = Undertow
      .builder()
      .addHttpListener(configPort.getOrElse(0), "localhost")
      .setHandler(deployment.start())
      .build()
    undertowServer.start()

    val listenerInfo = undertowServer.getListenerInfo()
    val port =
      listenerInfo.get(0).getAddress().asInstanceOf[InetSocketAddress].getPort()

    if (!configPort.isDefined) {
      McpConfig.writeConfig(port, projectName, projectPath, editor)
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

  override def cancel(): Unit = cancelable.cancel()

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
      new Tool("compile-file", "Compile a chosen Scala file", schema),
      withErrorHandling { (exchange, arguments) =>
        val path = arguments.getFileInFocus
        compilations
          .compileFile(path)
          .map {
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
          .toMono
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
        val symbolTypes = arguments
          .getAs[ArrayList[String]]("symbolType")
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
