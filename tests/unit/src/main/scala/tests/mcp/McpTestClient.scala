package tests.mcp

import java.time.Duration

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.MetalsEnrichments.XtensionJavaFuture
import scala.meta.internal.metals.mcp.MetalsMcpServer

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

class TestMcpClient(url: String, val port: Int)(implicit ec: ExecutionContext)
    extends TestMcpBaseClient {
  private val jsonMapper = new JacksonMcpJsonMapper(objectMapper)
  private val transport = HttpClientStreamableHttpTransport
    .builder(url)
    .endpoint(MetalsMcpServer.mcpEndpoint)
    .build()
  private val client =
    McpClient.async(transport).requestTimeout(Duration.ofMinutes(5)).build()

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

  override def initialize(): Future[InitializeResult] = {
    client.initialize().toFuture().asScala
  }

  override def shutdown(): Future[Unit] = {
    client.closeGracefully().toFuture().asScala.map(_ => ())
  }

  /** Alias for formatFile, kept for backward compatibility */
  def format(filePath: String): Future[String] = formatFile(filePath)

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

  def typedGlobSearch(
      query: String,
      symbolTypes: String,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("query", query)
    params.put("symbolType", symbolTypes)
    callTool("typed-glob-search", params).map(_.mkString)
  }
}
