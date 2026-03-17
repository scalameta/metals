package tests.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments.XtensionJavaFuture

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

/**
 * Test client for MCP servers using stdio transport.
 *
 * This client spawns the metals-mcp server as a subprocess and communicates
 * with it via stdin/stdout using the MCP protocol.
 *
 * @param workspacePath The workspace path to pass to the server
 * @param classpath The classpath for running the metals-mcp server
 * @param mainClass The main class to run (default: scala.meta.metals.McpMain)
 */
class TestMcpStdioClient(
    workspacePath: Path,
    classpath: String,
    mainClass: String = "scala.meta.metals.McpMain",
)(implicit ec: ExecutionContext) {

  private val objectMapper = new ObjectMapper()
  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)

  // Create temp directory for server output
  private val tempDir = Files.createTempDirectory("metals-mcp-stdio-test")

  // Build server parameters
  private val params = ServerParameters
    .builder("java")
    .args(
      "-cp",
      classpath,
      mainClass,
      "--workspace",
      workspacePath.toString,
      "--transport",
      "stdio",
    )
    .build()

  private val transport = new StdioClientTransport(params, jsonMapper)
  private val client =
    McpClient.async(transport).requestTimeout(Duration.ofMinutes(5)).build()

  private def callTool(
      toolName: String,
      params: com.fasterxml.jackson.databind.node.ObjectNode,
  ): Future[List[String]] = {
    val callToolRequest =
      new CallToolRequest(
        jsonMapper,
        toolName,
        objectMapper.writeValueAsString(params),
      )
    client
      .callTool(callToolRequest)
      .toFuture()
      .toScala
      .map(result =>
        result.content.asScala.collect { case text: TextContent =>
          text.text
        }.toList
      )
  }

  def initialize(): Future[InitializeResult] = {
    client.initialize().toFuture().asScala
  }

  def shutdown(): Future[Unit] = {
    client.closeGracefully().toFuture().asScala.map(_ => ()).recover {
      case NonFatal(_) => ()
    }
  }

  def listModules(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("list-modules", params).map(_.mkString)
  }

  def compileModule(module: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("module", module)
    callTool("compile-module", params).map(_.mkString)
  }

  def compileFile(filePath: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fileInFocus", filePath)
    callTool("compile-file", params).map(_.mkString)
  }

  def formatFile(filePath: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fileInFocus", filePath)
    callTool("format-file", params).map(_.mkString)
  }

  def findDep(
      organization: String,
      name: Option[String] = None,
      version: Option[String] = None,
  ): Future[List[String]] = {
    val params = objectMapper.createObjectNode()
    params.put("organization", organization)
    name.foreach(n => params.put("name", n))
    version.foreach(v => params.put("version", v))
    callTool("find-dep", params)
  }

  def typedGlobSearch(
      query: String,
      symbolTypes: List[String],
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("query", query)
    val symbolTypeArray = objectMapper.createArrayNode()
    symbolTypes.foreach(symbolTypeArray.add)
    params.set("symbolType", symbolTypeArray)
    callTool("typed-glob-search", params).map(_.mkString)
  }

  def listScalafixRules(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("list-scalafix-rules", params).map(_.mkString)
  }

  /** Clean up temp directory */
  def cleanup(): Unit = {
    try {
      Files
        .walk(tempDir)
        .sorted(Comparator.reverseOrder[Path]())
        .forEach(Files.delete)
    } catch {
      case NonFatal(_) =>
    }
  }
}

object TestMcpStdioClient {

  /**
   * Create a TestMcpStdioClient using the current classpath.
   * This is useful for tests running in sbt where the classpath is already set up.
   */
  def apply(
      workspacePath: Path,
  )(implicit ec: ExecutionContext): TestMcpStdioClient = {
    val classpath = System.getProperty("java.class.path")
    new TestMcpStdioClient(workspacePath, classpath)
  }

  /**
   * Create a TestMcpStdioClient using a specific classpath from built artifacts.
   */
  def withClasspath(
      workspacePath: Path,
      classpath: String,
  )(implicit ec: ExecutionContext): TestMcpStdioClient = {
    new TestMcpStdioClient(workspacePath, classpath)
  }
}
