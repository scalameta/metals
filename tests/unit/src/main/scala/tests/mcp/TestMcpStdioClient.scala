package tests.mcp

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments.XtensionJavaFuture

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

/** Spawns metals-mcp server as a subprocess and communicates via stdin/stdout. */
class TestMcpStdioClient(
    workspacePath: Path,
    classpath: String,
    mainClass: String = "scala.meta.metals.McpMain",
)(implicit ec: ExecutionContext)
    extends TestMcpBaseClient {

  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)

  private val javaExecutable: String = {
    val javaHome = System.getProperty("java.home")
    val execName = if (File.separatorChar == '\\') "java.exe" else "java"
    Paths.get(javaHome, "bin", execName).toString
  }

  private val serverParameters: ServerParameters =
    ServerParameters
      .builder(javaExecutable)
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

  private val transport = new StdioClientTransport(serverParameters, jsonMapper)
  private val client =
    McpClient
      .async(transport)
      .requestTimeout(Duration.ofMinutes(5))
      .initializationTimeout(Duration.ofMinutes(5))
      .build()

  override protected def callTool(
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

  override def initialize(): Future[InitializeResult] =
    client.initialize().toFuture().asScala

  override def shutdown(): Future[Unit] =
    client.closeGracefully().toFuture().asScala.map(_ => ()).recover {
      case NonFatal(_) => ()
    }

  def typedGlobSearch(
      query: String,
      symbolTypes: List[String],
      fileInFocus: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("query", query)
    val symbolTypeArray = objectMapper.createArrayNode()
    symbolTypes.foreach(symbolTypeArray.add)
    params.set("symbolType", symbolTypeArray)
    fileInFocus.foreach(f => params.put("fileInFocus", f))
    callTool("typed-glob-search", params).map(_.mkString)
  }

  def globSearch(
      query: String,
      fileInFocus: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("query", query)
    fileInFocus.foreach(f => params.put("fileInFocus", f))
    callTool("glob-search", params).map(_.mkString)
  }

  def inspect(
      fqcn: String,
      fileInFocus: Option[String] = None,
      module: Option[String] = None,
      searchAllTargets: Boolean = false,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fqcn", fqcn)
    fileInFocus.foreach(f => params.put("fileInFocus", f))
    module.foreach(m => params.put("module", m))
    if (searchAllTargets) params.put("searchAllTargets", true)
    callTool("inspect", params).map(_.mkString)
  }

  def getDocs(
      fqcn: String,
      fileInFocus: Option[String] = None,
      module: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fqcn", fqcn)
    fileInFocus.foreach(f => params.put("fileInFocus", f))
    module.foreach(m => params.put("module", m))
    callTool("get-docs", params).map(_.mkString)
  }

  def getUsages(
      fqcn: String,
      fileInFocus: Option[String] = None,
      module: Option[String] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fqcn", fqcn)
    fileInFocus.foreach(f => params.put("fileInFocus", f))
    module.foreach(m => params.put("module", m))
    callTool("get-usages", params).map(_.mkString)
  }

  def importBuild(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("import-build", params).map(_.mkString)
  }

  /** Polls list-modules until the expected module appears (build server needs time to index). */
  def waitForIndexing(
      expectedModule: String,
      maxAttempts: Int = 60,
      delayMs: Long = 500,
  ): Future[Unit] = {
    def poll(attempt: Int): Future[Unit] = {
      if (attempt >= maxAttempts) {
        Future.failed(
          new java.util.concurrent.TimeoutException(
            s"Indexing timeout: module '$expectedModule' not found after ${maxAttempts * delayMs}ms"
          )
        )
      } else {
        listModules().flatMap { modules =>
          if (modules.contains(expectedModule)) {
            Future.successful(())
          } else {
            Future {
              Thread.sleep(delayMs)
            }.flatMap(_ => poll(attempt + 1))
          }
        }
      }
    }
    poll(0)
  }

  def runTest(
      testClass: String,
      testFile: Option[String] = None,
      testName: Option[String] = None,
      verbose: Boolean = false,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("testClass", testClass)
    testFile.foreach(f => params.put("testFile", f))
    testName.foreach(n => params.put("testName", n))
    if (verbose) params.put("verbose", true)
    callTool("test", params).map(_.mkString)
  }

  /** Workspace cleanup is handled by test suites; this method is kept for API compatibility. */
  def cleanup(): Unit = {}
}

object TestMcpStdioClient {

  /** Creates a client using the current classpath (for tests running in sbt). */
  def apply(
      workspacePath: Path
  )(implicit ec: ExecutionContext): TestMcpStdioClient = {
    val classpath = System.getProperty("java.class.path")
    new TestMcpStdioClient(workspacePath, classpath)
  }

  def withClasspath(
      workspacePath: Path,
      classpath: String,
  )(implicit ec: ExecutionContext): TestMcpStdioClient = {
    new TestMcpStdioClient(workspacePath, classpath)
  }
}
