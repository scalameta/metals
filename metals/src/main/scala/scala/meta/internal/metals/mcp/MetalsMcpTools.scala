package scala.meta.internal.metals.mcp

import java.nio.file.Path
import java.util.Arrays
import java.util.function.BiFunction
import java.util.{List => JList}
import java.util.{Map => JMap}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
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
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.SymbolType
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.StatusCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Content
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.services.LanguageClient
import reactor.core.publisher.Mono

/**
 * Shared trait for MCP server implementations.
 * Contains all tool creation methods and helpers that are common
 * between HTTP and stdio transport implementations.
 */
trait MetalsMcpTools extends Cancelable {

  // Abstract dependencies - concrete classes must provide these
  protected def queryEngine: McpQueryEngine
  protected def projectPath: AbsolutePath
  protected def compilations: Compilations
  protected def focusedDocument: () => Option[AbsolutePath]
  protected def diagnostics: Diagnostics
  protected def buildTargets: BuildTargets
  protected def mcpTestRunner: McpTestRunner
  protected def clientName: String
  protected def projectName: String
  protected def languageClient: LanguageClient
  protected def connectionProvider: ConnectionProvider
  protected def scalaVersionSelector: ScalaVersionSelector
  protected def formattingProvider: FormattingProvider
  protected def scalafixLlmRuleProvider: ScalafixLlmRuleProvider
  protected def indexingPromise: Promise[Unit]
  protected implicit def ec: ExecutionContext

  // Shared mutable state
  protected val cancelable = new MutableCancelable()

  // Shared fields - lazy because clientName is provided by subclass constructor
  protected lazy val client: Client =
    Client.allClients.find(_.names.contains(clientName)).getOrElse(NoClient)

  protected val objectMapper = new ObjectMapper()

  protected val jsonMapper = new JacksonMcpJsonMapper(objectMapper)

  protected def createContent(text: String): JList[Content] = {
    Arrays.asList(new TextContent(text))
  }

  // Abstract - each transport implements differently
  def run(): Unit

  // Default implementation - can be overridden for additional cleanup
  override def cancel(): Unit = cancelable.cancel()

  // Tool creation methods
  protected def importBuildTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    val tool = Tool
      .builder()
      .name("import-build")
      .description(
        "Import the build to IDE. Should be performed after any build changes, e.g. adding dependencies or any changes in build.sbt."
      )
      .inputSchema(jsonMapper, schema)
      .build()

    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, _) =>
        connectionProvider
          .slowConnectToBuildServer(forceImport = true)
          .map {
            case BuildChange.None =>
              CallToolResult
                .builder()
                .content(createContent("No changes detected"))
                .isError(false)
                .build()
            case BuildChange.Reconnected =>
              CallToolResult
                .builder()
                .content(createContent("Reconnected to build server"))
                .isError(false)
                .build()
            case BuildChange.Reloaded =>
              CallToolResult
                .builder()
                .content(createContent("Build reloaded"))
                .isError(false)
                .build()
            case BuildChange.Failed =>
              CallToolResult
                .builder()
                .content(createContent("Failed to reimport build."))
                .isError(true)
                .build()
            case BuildChange.Cancelled =>
              CallToolResult
                .builder()
                .content(createContent("Reimport cancelled by the user."))
                .isError(true)
                .build()
          }
          .toMono
      },
    )

  }

  protected def createCompileTool(): AsyncToolSpecification = {
    val schema = """{"type": "object", "properties": { }}"""
    val tool = Tool
      .builder()
      .name("compile-full")
      .description("Compile the whole Scala project")
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
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
            CallToolResult
              .builder()
              .content(createContent(content))
              .isError(false)
              .build()
          }
          .toMono
      },
    )
  }

  protected def createFileCompileTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("compile-file")
      .description("Compile a chosen Scala file")
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val path = arguments.getFileInFocus
        if (path.exists) {
          compilations
            .compileFile(path)
            .map {
              case c if c.getStatusCode == StatusCode.CANCELLED =>
                CallToolResult
                  .builder()
                  .content(
                    createContent(
                      "Compilation cancelled or incorrect file path"
                    )
                  )
                  .isError(true)
                  .build()
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

                CallToolResult
                  .builder()
                  .content(createContent(content))
                  .isError(false)
                  .build()
            }
            .toMono
        } else {
          Future
            .successful(
              CallToolResult
                .builder()
                .content(createContent(s"Error: File not found: $path"))
                .isError(true)
                .build()
            )
            .toMono
        }
      },
    )
  }

  protected def createCompileModuleTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("compile-module")
      .description("Compile a chosen Scala module")
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val module = arguments.getAs[String]("module")
        (buildTargets.allScala ++ buildTargets.allJava).find(
          _.displayName == module
        ) match {
          case Some(target) =>
            compilations
              .compileTarget(target.id)
              .map { compileResult =>
                if (compileResult.getStatusCode == StatusCode.CANCELLED) {
                  CallToolResult
                    .builder()
                    .content(createContent("Compilation cancelled"))
                    .isError(true)
                    .build()
                } else {
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
                  CallToolResult
                    .builder()
                    .content(createContent(result))
                    .isError(false)
                    .build()
                }
              }
              .toMono
          case None =>
            Future
              .successful(
                CallToolResult
                  .builder()
                  .content(createContent(s"Error: Module not found: $module"))
                  .isError(true)
                  .build()
              )
              .toMono
        }
      },
    )
  }

  protected def inModuleErrors(
      buildTarget: BuildTargetIdentifier
  ): Option[String] = {
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

  protected def upstreamModulesErros(
      buildTarget: BuildTargetIdentifier,
      fileOrModule: String,
  ): Option[String] = {
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

  protected def createTestTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("test")
      .description(
        """|Run Scala test suite. Execute specific test classes and individual test methods.
           |Supports verbose output and can run tests from any testing
           |framework (ScalaTest, MUnit, etc.)""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
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
              CallToolResult
                .builder()
                .content(createContent(content))
                .isError(false)
                .build()
            )
          case Left(error) =>
            Future.successful(
              CallToolResult
                .builder()
                .content(createContent(s"Error: $error"))
                .isError(true)
                .build()
            )
        }).toMono
      },
    )
  }

  protected def createGlobSearchTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("glob-search")
      .description(
        """|Search for symbols using glob pattern. Find packages, classes, objects, methods, traits,
           |and other Scala symbols by partial name matching. Returns symbol locations
           |and signatures from the entire project workspace.
           |Use this if you encounter unknown API, for example proprietary libraries.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val query = arguments.getAs[String]("query")
        val path = arguments.getFileInFocus
        indexingPromise.future.flatMap { _ =>
          queryEngine
            .globSearch(query, Set.empty, path)
            .map(result =>
              CallToolResult
                .builder()
                .content(createContent(result.map(_.show).mkString("\n")))
                .isError(false)
                .build()
            )
        }.toMono
      },
    )
  }

  protected def createTypedGlobSearchTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("typed-glob-search")
      .description(
        """|Search for symbols by type using glob pattern. Filter symbol search results
           |by specific symbol types (package, class, object, function, method, trait).
           |More precise than glob-search when you know the symbol type you're looking for.
           |Use this if you encounter unknown API, for example proprietary libraries.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
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

        indexingPromise.future.flatMap { _ =>
          queryEngine
            .globSearch(query, symbolTypesSet, path)
            .map(result =>
              CallToolResult
                .builder()
                .content(createContent(result.map(_.show).mkString("\n")))
                .isError(false)
                .build()
            )
        }.toMono
      },
    )
  }

  protected def createInspectTool(): AsyncToolSpecification = {
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
            "description": "The current file in focus for context. If not provided, will use first available build target."
          },
          "module": {
            "type": "string",
            "description": "Explicit module (build target) name to use for context, e.g. 'core', 'services'. Takes precedence over fileInFocus."
          },
          "searchAllTargets": {
            "type": "boolean",
            "description": "If true, search all build targets and combine results. Useful for understanding cross-module visibility of project symbols.",
            "default": false
          }
        },
        "required": ["fqcn"]
      }
    """
    val tool = Tool
      .builder()
      .name("inspect")
      .description(
        """|Inspect a chosen Scala symbol.
           |For packages, objects and traits returns list of members.
           |For classes returns list of members and constructors.
           |For methods returns signatures of all overloaded methods.
           |
           |When no fileInFocus is provided, automatically uses the first available
           |build target. Use searchAllTargets=true for comprehensive cross-module inspection.
           |Use 'module' to explicitly specify which build target to use.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        val pathOpt = arguments.getFileInFocusOpt
        val moduleOpt = arguments.getOptNoEmptyString("module")
        val searchAllTargets = arguments
          .getOptAs[Boolean]("searchAllTargets")
          .getOrElse(false)
        indexingPromise.future.flatMap { _ =>
          queryEngine
            .inspect(fqcn, pathOpt, moduleOpt, searchAllTargets)
            .map(result =>
              CallToolResult
                .builder()
                .content(createContent(result.show))
                .isError(false)
                .build()
            )
        }.toMono
      },
    )
  }

  protected def createGetDocsTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "description": "Fully qualified name of the symbol to get documentation for"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context. If not provided, will use first available build target."
          },
          "module": {
            "type": "string",
            "description": "Explicit module (build target) name to use for context, e.g. 'core', 'services'. Takes precedence over fileInFocus."
          }
        },
        "required": ["fqcn"]
      }
    """
    val tool = Tool
      .builder()
      .name("get-docs")
      .description(
        """|Get documentation for a chosen Scala symbol. Retrieves ScalaDoc comments,
           |parameter descriptions, return types, and usage examples for classes, methods,
           |functions, and other symbols using their fully qualified name.
           |
           |When no fileInFocus is provided, automatically uses the first available
           |build target for context, making this tool usable from MCP clients
           |without editor integration.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        val pathOpt = arguments.getFileInFocusOpt
        val moduleOpt = arguments.getOptNoEmptyString("module")
        indexingPromise.future.map { _ =>
          queryEngine.getDocumentation(fqcn, pathOpt, moduleOpt) match {
            case Some(result) =>
              CallToolResult
                .builder()
                .content(createContent(result.show))
                .isError(false)
                .build()
            case None =>
              CallToolResult
                .builder()
                .content(createContent("Error: Symbol not found"))
                .isError(true)
                .build()
          }
        }.toMono
      },
    )
  }

  protected def createGetUsagesTool(): AsyncToolSpecification = {
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
            "description": "The current file in focus for context. If not provided, will use first available build target."
          },
          "module": {
            "type": "string",
            "description": "Explicit module (build target) name to use for context, e.g. 'core', 'services'. Takes precedence over fileInFocus."
          }
        },
        "required": ["fqcn"]
      }
    """
    val tool = Tool
      .builder()
      .name("get-usages")
      .description(
        """|Get usages for a chosen Scala symbol. Find all references and usages of classes,
           |methods, variables, and other symbols across the entire project. Returns precise
           |locations with file paths and line numbers for refactoring and code analysis.
           |
           |When no fileInFocus is provided, automatically uses the first available
           |build target for context, making this tool usable from MCP clients
           |without editor integration.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (exchange, arguments) =>
        val fqcn = arguments.getFqcn
        val pathOpt = arguments.getFileInFocusOpt
        val moduleOpt = arguments.getOptNoEmptyString("module")
        indexingPromise.future.map { _ =>
          val result = queryEngine.getUsages(fqcn, pathOpt, moduleOpt)
          CallToolResult
            .builder()
            .content(createContent(result.show(projectPath)))
            .isError(false)
            .build()
        }.toMono
      },
    )
  }

  protected object FindDepKey {
    val version = "version"
    val name = "name"
    val organization = "organization"
  }

  protected def createFindDepTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("find-dep")
      .description(
        """|Find a dependency using coursier, optionally specify organization, name, and version.
           |It will try to return completions for the dependency string.
           |At a minimum you should specify the dependency organization. When only organization is
           |specified, it will return all organizations with the specified prefix. If name is additionally
           |specified, it will return all names with the specified prefix in the organization. If version is additionally
           |specified, it will return all versions with the specified prefix in the organization and name.
           |""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
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
            if (key == FindDepKey.version)
              McpMessages.FindDep.versionMessage(completed.headOption)
            else
              McpMessages.FindDep.dependencyReturnMessage(
                key,
                completed.distinct,
              )

          }
        }.headOption
          .getOrElse(McpMessages.FindDep.noCompletionsFound)

        Future
          .successful(
            CallToolResult
              .builder()
              .content(createContent(completions))
              .isError(false)
              .build()
          )
          .toMono
      },
    )
  }

  protected def createListModulesTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": { }
        |} 
        |""".stripMargin
    val tool = Tool
      .builder()
      .name("list-modules")
      .description(
        "Return the list of modules (build targets) available in the project."
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (_, _) =>
        Future {
          val modules =
            buildTargets.allBuildTargetIds.flatMap(
              buildTargets.jvmTarget(_).map(_.displayName)
            )
          CallToolResult
            .builder()
            .content(
              createContent(
                s"Available modules (build targets):${modules.map(module => s"\n- $module").mkString}"
              )
            )
            .isError(false)
            .build()
        }.toMono
      },
    )
  }

  protected def createFormatTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("format-file")
      .description("Format a Scala file and return the formatted text")
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (_, arguments) =>
        val path = arguments.getFileInFocus
        if (path.exists && path.isScalaFilename) {
          val cancelChecker = new org.eclipse.lsp4j.jsonrpc.CancelChecker {
            override def isCanceled(): Boolean = false
            override def checkCanceled(): Unit = ()
          }

          formattingProvider
            .formatForMcp(path, projectPath, cancelChecker)
            .flatMap {
              case Left(errorMessage) =>
                Future.successful(
                  CallToolResult
                    .builder()
                    .content(createContent(errorMessage))
                    .isError(true)
                    .build()
                )
              case Right(Nil) =>
                Future.successful(
                  CallToolResult
                    .builder()
                    .content(
                      createContent("File is already properly formatted.")
                    )
                    .isError(false)
                    .build()
                )
              case Right(formattedText) =>
                languageClient
                  .applyEdit(
                    new ApplyWorkspaceEditParams(
                      new WorkspaceEdit(
                        Map(
                          path.toURI.toString -> formattedText.asJava
                        ).asJava
                      )
                    )
                  )
                  .asScala
                  .map { response =>
                    if (response.isApplied()) {
                      CallToolResult
                        .builder()
                        .content(createContent(s"$path was formatted"))
                        .isError(false)
                        .build()
                    } else {
                      CallToolResult
                        .builder()
                        .content(
                          createContent(
                            s"Failed to format $path: ${Option(response.getFailureReason()).getOrElse("unknown error")}"
                          )
                        )
                        .isError(true)
                        .build()
                    }
                  }
            }
            .toMono
        } else {
          Future
            .successful(
              CallToolResult
                .builder()
                .content(
                  createContent(
                    s"Error: File not found or not a Scala file: $path"
                  )
                )
                .isError(true)
                .build()
            )
            .toMono
        }
      },
    )
  }

  protected def createGenerateScalafixRuleTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": {
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
        |      "items": {
        |        "type": "string"
        |      },
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
        |  "required": ["description", "ruleImplementation"]
        |} 
        |""".stripMargin
    val tool = Tool
      .builder()
      .name("generate-scalafix-rule")
      .description(
        """|Generate a scalafix rule and run it on the current project.
           |
           |Use this tool whenever you want to migrate a particular code pattern inside the entire codebase.
           |This might include fixing code smells, refactorings or migrating between versions of Scala or a particular library.
           |The generated rule will be created in .metals/rules directory and can be later invoked using the `run-scalafix-rule` tool.
           |When a rule with the same name already exists, it will be overwritten. This is useful if you want to update
           |the rule implementation.
           |""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (_, arguments) =>
        import scala.meta._
        val ruleImplementation = arguments.getAs[String]("ruleImplementation")
        val description = arguments.getAs[String]("description")
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
            ruleImplementation,
            description,
            modules,
            runOn.toList,
          )
        resultingFuture.map {
          case Right(res) =>
            CallToolResult
              .builder()
              .content(
                createContent(
                  s"Created and ran Scalafix rule ${res.ruleName} successfully"
                )
              )
              .isError(false)
              .build()
          case Left(error) =>
            CallToolResult
              .builder()
              .content(createContent(errorMessage(error)))
              .isError(true)
              .build()
        }.toMono
      },
    )
  }

  protected def createRunScalafixRuleTool(): AsyncToolSpecification = {
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
    val tool = Tool
      .builder()
      .name("run-scalafix-rule")
      .description(
        "Run a specific previously existing Scalafix rule (from curated rules or previously created rules) on the focused file or all files"
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
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
            CallToolResult
              .builder()
              .content(createContent("Scalafix rule run successfully"))
              .isError(false)
              .build()
          }
          .recover { case error =>
            CallToolResult
              .builder()
              .content(createContent(error.getMessage))
              .isError(true)
              .build()
          }
          .toMono
      },
    )
  }

  protected def createListScalafixRulesTool(): AsyncToolSpecification = {
    val schema =
      """{
        |  "type": "object",
        |  "properties": { }
        |} 
        |""".stripMargin
    val tool = Tool
      .builder()
      .name("list-scalafix-rules")
      .description(
        "List currently available scalafix rules from .metals/rules directory. They were previously created by the `run-scalafix-rule` tool."
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (_, _) =>
        Future {
          val allRules = ScalafixLlmRuleProvider.allRules(projectPath)
          val content =
            allRules.toList.sortBy(_._1).map { case (ruleName, description) =>
              s"- $ruleName: $description"
            }
          CallToolResult
            .builder()
            .content(
              createContent(
                s"Available scalafix rules:\n${content.mkString("\n")}"
              )
            )
            .isError(false)
            .build()
        }.toMono
      },
    )
  }

  private def createGetSourceTool(): AsyncToolSpecification = {
    val schema = """
      {
        "type": "object",
        "properties": {
          "fqcn": {
            "type": "string",
            "description": "Fully qualified name of the symbol to get source for"
          },
          "fileInFocus": {
            "type": "string",
            "description": "The current file in focus for context. If not provided, will use first available build target."
          },
          "module": {
            "type": "string",
            "description": "Explicit module (build target) name to use for context, e.g. 'core', 'services'. Takes precedence over fileInFocus."
          }
        },
        "required": ["fqcn"]
      }
    """
    val tool = Tool
      .builder()
      .name("get-source")
      .description(
        """|Get the source file contents for a given Scala symbol. Returns the full content
           |of the file containing the symbol, allowing you to read the implementation.
           |Useful for understanding library code, inspecting dependencies, and code analysis.
           |
           |When no fileInFocus is provided, automatically uses the first available
           |build target for context.""".stripMargin
      )
      .inputSchema(jsonMapper, schema)
      .build()
    new AsyncToolSpecification(
      tool,
      withErrorHandling { (_, arguments) =>
        val fqcn = arguments.getFqcn
        val pathOpt = arguments.getFileInFocusOpt
        val moduleOpt = arguments.getOptNoEmptyString("module")
        Future {
          queryEngine.getSource(fqcn, pathOpt, moduleOpt) match {
            case Some((filePath, content)) =>
              CallToolResult
                .builder()
                .content(
                  createContent(s"// Source file: $filePath\n$content")
                )
                .isError(false)
                .build()
            case None =>
              CallToolResult
                .builder()
                .content(
                  createContent(s"Error: Source not found for symbol: $fqcn")
                )
                .isError(true)
                .build()
          }
        }.toMono
      },
    )
  }

  protected def registerAllTools(
      asyncServer: io.modelcontextprotocol.server.McpAsyncServer
  ): Unit = {
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
    asyncServer.addTool(createGetSourceTool()).subscribe()
  }

  protected def buildCapabilities()
      : io.modelcontextprotocol.spec.McpSchema.ServerCapabilities =
    io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
      .builder()
      .tools(true)
      .logging()
      .build()

  // Helper methods
  protected def withErrorHandling(
      f: (McpAsyncServerExchange, JMap[String, Object]) => Mono[CallToolResult]
  ): BiFunction[
    McpAsyncServerExchange,
    CallToolRequest,
    Mono[CallToolResult],
  ] = { (exchange, request) =>
    val arguments = request.arguments()
    try {
      f(exchange, arguments).onErrorResume { e =>
        scribe.warn(
          s"Error while processing request: ${e.getMessage}, arguments: ${arguments.toJson}, stacktrace:" +
            e.getStackTrace.mkString("\n")
        )
        Mono.just(
          CallToolResult
            .builder()
            .content(
              createContent(
                s"Error: ${e.getMessage}, arguments: ${arguments.toJson}"
              )
            )
            .isError(true)
            .build()
        )
      }
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Error while processing request: ${e.getMessage}, arguments: ${arguments.toJson}, stacktrace:" +
            e.getStackTrace.mkString("\n")
        )
        Mono.just(
          CallToolResult
            .builder()
            .content(
              createContent(
                s"Error: ${e.getMessage}, arguments: ${arguments.toJson}"
              )
            )
            .isError(true)
            .build()
        )
    }
  }

  // Implicit classes
  implicit protected class XtensionFuture[T](val f: Future[T]) {
    def toMono: Mono[T] = Mono.fromFuture(f.asJava)
  }

  implicit protected class XtensionArguments(
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
        case value => Try(value.asInstanceOf[T]).toOption
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

    /**
     * Serialize arguments to JSON string for error reporting.
     */
    def toJson: String = {
      objectMapper.writeValueAsString(arguments)
    }
  }
}
