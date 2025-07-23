package tests.mcp

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.MetalsEnrichments.XtensionJavaFuture

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

class TestMcpClient(url: String, val port: Int)(implicit ec: ExecutionContext) {
  private val objectMapper = new ObjectMapper()
  private val transport = new HttpClientSseClientTransport(url)
  private val client = McpClient.async(transport).build()

  private def callTool(
      toolName: String,
      params: com.fasterxml.jackson.databind.node.ObjectNode,
  ): Future[List[String]] = {
    val callToolRequest =
      new CallToolRequest(toolName, objectMapper.writeValueAsString(params))
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
    client.closeGracefully().toFuture().asScala.map(_ => ())
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

  def compileFile(filePath: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("fileInFocus", filePath)
    callTool("compile-file", params).map(_.mkString)
  }

  def compileModule(module: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("module", module)
    callTool("compile-module", params).map(_.mkString)
  }

  def listModules(): Future[String] = {
    val params = objectMapper.createObjectNode()
    callTool("list-modules", params).map(_.mkString)
  }
}
