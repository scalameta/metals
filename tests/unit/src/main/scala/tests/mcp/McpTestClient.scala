package tests.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.MetalsEnrichments.XtensionJavaFuture
import scala.meta.internal.metals.mcp.MetalsMcpServer

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.InitializeResult
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification
import io.modelcontextprotocol.spec.McpSchema.TextContent
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

class TestMcpClient(url: String, val port: Int)(implicit ec: ExecutionContext)
    extends TestMcpBaseClient {
  private val jsonMapper = new JacksonMcpJsonMapper(
    JsonMapper.builder().build()
  )
  private val transport = HttpClientStreamableHttpTransport
    .builder(url)
    .endpoint(MetalsMcpServer.mcpEndpoint)
    .build()
  private val firstProgress = Promise[ProgressNotification]()
  private val client =
    McpClient
      .async(transport)
      .requestTimeout(Duration.ofMinutes(5))
      .progressConsumer { notification =>
        firstProgress.trySuccess(notification)
        Mono.empty[Void]()
      }
      .build()

  def firstProgressNotification: Future[ProgressNotification] =
    firstProgress.future

  def sendRawInitialize(
      requestBody: String
  ): Future[HttpResponse[String]] = {
    val httpClient = HttpClient.newHttpClient()
    val httpRequest = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$url${MetalsMcpServer.mcpEndpoint}"))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()
    httpClient
      .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
      .toScala
  }

  override protected def callTool(
      toolName: String,
      params: tools.jackson.databind.node.ObjectNode,
  ): Future[List[String]] = callTool(toolName, params, progressToken = None)

  private def callTool(
      toolName: String,
      params: tools.jackson.databind.node.ObjectNode,
      progressToken: Option[String],
  ): Future[List[String]] = {
    val requestBuilder = CallToolRequest
      .builder(toolName)
      .arguments(jsonMapper, objectMapper.writeValueAsString(params))
    progressToken.foreach(requestBuilder.progressToken)
    val callToolRequest = requestBuilder.build()
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

  def globSearchWithProgress(
      query: String,
      progressToken: String,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("query", query)
    callTool("glob-search", params, Some(progressToken)).map(_.mkString)
  }

  def compileFullWithProgress(progressToken: String): Future[String] =
    callTool(
      "compile-full",
      objectMapper.createObjectNode(),
      Some(progressToken),
    )
      .map(_.mkString)

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
