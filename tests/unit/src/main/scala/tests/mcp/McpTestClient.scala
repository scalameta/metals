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
import scala.meta.internal.metals.DebugSession

class TestMcpClient(url: String, val port: Int)(implicit ec: ExecutionContext) {
  private val objectMapper = new ObjectMapper()
  private val transport = new HttpClientSseClientTransport(url)
  private val client = McpClient
    .async(transport)
    .requestTimeout(java.time.Duration.ofSeconds(240))
    .build()

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

  // Generic call method for tests
  def call(toolName: String, args: Map[String, Any]): Future[String] = {
    val params = objectMapper.createObjectNode()
    args.foreach {
      case (key, value: String) => params.put(key, value)
      case (key, value: Int) => params.put(key, value)
      case (key, value: Boolean) => params.put(key, value)
      case (key, value) => params.put(key, value.toString)
    }
    callTool(toolName, params).map(_.mkString)
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

  def debugMain(
      mainClass: String,
      module: Option[String] = None,
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      initialBreakpoints: List[Map[String, Any]] = List.empty,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("mainClass", mainClass)
    module.foreach(m => params.put("module", m))
    if (args.nonEmpty) {
      val argsArray = objectMapper.createArrayNode()
      args.foreach(argsArray.add)
      params.set("args", argsArray)
    }
    if (env.nonEmpty) {
      val envNode = objectMapper.createObjectNode()
      env.foreach { case (k, v) => envNode.put(k, v) }
      params.set("env", envNode)
    }
    val bArray = objectMapper.createArrayNode()
    initialBreakpoints.foreach { breakpoint =>
      val bNode = objectMapper.createObjectNode()
      breakpoint.foreach {
        case (key, value: String) => bNode.put(key, value)
        case (key, value: Int) => bNode.put(key, value)
        case (key, value: Any) => bNode.put(key, value.toString)
      }
      bArray.add(bNode)
    }
    params.set("initialBreakpoints", bArray)
    callTool("debug-main", params).map(_.mkString)
  }

  def debugTest(
      testClass: String,
      module: Option[String] = None,
      testMethod: Option[String] = None,
      initialBreakpoints: Option[List[Map[String, Any]]] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("testClass", testClass)
    module.foreach(m => params.put("module", m))
    testMethod.foreach(m => params.put("testMethod", m))
    initialBreakpoints.foreach { b =>
      val bArray = objectMapper.createArrayNode()
      b.foreach { breakpoint =>
        val bNode = objectMapper.createObjectNode()
        breakpoint.foreach {
          case (key, value: String) => bNode.put(key, value)
          case (key, value: Int) => bNode.put(key, value)
          case (key, value: Any) => bNode.put(key, value.toString)
        }
        bArray.add(bNode)
      }
      params.set("initialBreakpoints", bArray)
      ()
    }
    callTool("debug-test", params).map(_.mkString)
  }

  def debugAttach(
      port: Int,
      hostName: Option[String] = None,
      module: Option[String] = None,
      initialBreakpoints: Option[List[Map[String, Any]]] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("port", port)
    hostName.foreach(h => params.put("hostName", h))
    module.foreach(m => params.put("module", m))
    initialBreakpoints.foreach { b =>
      val bArray = objectMapper.createArrayNode()
      b.foreach { breakpoint =>
        val bNode = objectMapper.createObjectNode()
        breakpoint.foreach {
          case (key, value: String) => bNode.put(key, value)
          case (key, value: Int) => bNode.put(key, value)
          case (key, value: Any) => bNode.put(key, value.toString)
        }
        bArray.add(bNode)
      }
      params.set("initialBreakpoints", bArray)
      ()
    }
    callTool("debug-attach", params).map(_.mkString)
  }

  def debugSessions(): Future[List[DebugSession]] = {
    val params = objectMapper.createObjectNode()
    callTool("debug-sessions", params).map(_.mkString).map { response =>
      val json = ujson.read(response)
      json("sessions").arr.map { session =>
        DebugSession(
          id = session("sessionId").str,
          name = session("sessionName").str,
          uri = session("debugUri").str,
        )
      }.toList
    }
  }

  def debugPause(
      sessionId: String,
      threadId: Option[Int] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    threadId.foreach(t => params.put("threadId", t))
    callTool("debug-pause", params).map(_.mkString)
  }

  def debugContinue(
      sessionId: String,
      threadId: Option[Int] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    threadId.foreach(t => params.put("threadId", t))
    callTool("debug-continue", params).map(_.mkString)
  }

  def debugStep(
      sessionId: String,
      threadId: Int,
      stepType: String,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    params.put("threadId", threadId)
    params.put("stepType", stepType)
    callTool("debug-step", params).map(_.mkString)
  }

  def debugEvaluate(
      sessionId: String,
      expression: String,
      frameId: Int,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    params.put("expression", expression)
    params.put("frameId", frameId)
    callTool("debug-evaluate", params).map(_.mkString)
  }

  def debugVariables(
      sessionId: String,
      threadId: Int,
      frameId: Option[Int] = None,
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    params.put("threadId", threadId)
    frameId.foreach(f => params.put("frameId", f))
    callTool("debug-variables", params).map(_.mkString)
  }

  def debugBreakpoints(
      sessionId: String,
      source: String,
      breakpoints: List[Map[String, Any]],
  ): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    params.put("source", source)
    val bArray = objectMapper.createArrayNode()
    breakpoints.foreach { breakpoint =>
      val bNode = objectMapper.createObjectNode()
      breakpoint.foreach {
        case (key, value: String) => bNode.put(key, value)
        case (key, value: Int) => bNode.put(key, value)
        case (key, value: Any) => bNode.put(key, value.toString)
      }
      bArray.add(bNode)
    }
    params.set("breakpoints", bArray)
    callTool("debug-breakpoints", params).map(_.mkString)
  }

  def debugTerminate(sessionId: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    callTool("debug-terminate", params).map(_.mkString)
  }

  def debugThreads(sessionId: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    callTool("debug-threads", params).map(_.mkString)
  }

  def debugStackTrace(sessionId: String, threadId: Int): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    params.put("threadId", threadId)
    callTool("debug-stack-trace", params).map(_.mkString)
  }

  def debugConnect(sessionId: String): Future[String] = {
    val params = objectMapper.createObjectNode()
    params.put("sessionId", sessionId)
    callTool("debug-connect", params).map(_.mkString)
  }

  def listResources(): Future[String] = {
    client.listResources().toFuture().asScala.map { result =>
      val resources = result.resources.asScala.map { resource =>
        Map(
          "uri" -> resource.uri,
          "name" -> resource.name,
          "description" -> resource.description,
          "mimeType" -> resource.mimeType,
        )
      }
      ujson.Obj("resources" -> resources).toString()
    }
  }

  def readResource(uri: String): Future[String] = {
    val request =
      new io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest(uri)
    client.readResource(request).toFuture().asScala.map { result =>
      val contents = result.contents.asScala.map {
        case textContent: io.modelcontextprotocol.spec.McpSchema.TextResourceContents =>
          Map(
            "uri" -> textContent.uri,
            "mimeType" -> textContent.mimeType,
            "text" -> textContent.text,
          )
        case other =>
          Map("type" -> other.getClass.getSimpleName)
      }
      ujson.Obj("contents" -> contents).toString()
    }
  }
}
