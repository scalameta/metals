package scala.meta.internal.metals.debug

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.meta.internal.metals.JsonParser.*
import com.google.gson.{JsonElement, JsonObject}
import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage

/**
 * Provides a Future-based API for MCP tools to interact with debug sessions.
 *
 * This wrapper converts DAP operations into Future-based calls that work well
 * with MCP's request-response pattern.
 */
class McpDebugSession(
    val sessionId: String,
    adapter: McpEndpoint,
)(implicit ec: ExecutionContext) {

  @volatile private var isInitialized = false
  private val initializationLock = new Object()

  private def ensureInitialized(): Future[Unit] = {
    if (isInitialized) {
      Future.successful(())
    } else {
      initializationLock.synchronized {
        if (isInitialized) {
          Future.successful(())
        } else {
          scribe.info(
            s"[McpDebugSession] Auto-initializing debug session: $sessionId"
          )
          // Perform the DAP initialization
          initialize().map { _ =>
            isInitialized = true
            ()
          }
        }
      }
    }
  }

  private def sendRequest[T: ClassTag](
      method: String,
      params: Any,
  ): Future[T] = {
    val request = new DebugRequestMessage()
    request.setMethod(method)
    request.setParams(params.toJson)

    scribe.info(
      s"[McpDebugSession] Sending $method request with params: ${params.toJson}"
    )

    adapter.sendRequest(request).map { response =>
      if (response.getError != null) {
        scribe.error(
          s"[McpDebugSession] $method request failed with error: ${response.getError.getMessage}"
        )
        scribe.error(
          s"[McpDebugSession] Error code: ${response.getError.getCode}, Data: ${response.getError.getData}"
        )
        throw new RuntimeException(
          s"Debug request failed: ${response.getError.getMessage}"
        )
      }
      scribe.info(
        s"[McpDebugSession] $method request succeeded, result type: ${Option(response.getResult).map(_.getClass.getSimpleName).getOrElse("null")}"
      )
      scribe.info(
        s"[McpDebugSession] $method response result: ${response.getResult}"
      )
      val result = response.getResult
      result match {
        case jsonObject: JsonObject => jsonObject.as[T].get
        case _ => result.asInstanceOf[T]
      }
    }
  }

  def continue(args: ContinueArguments): Future[ContinueResponse] = {
    sendRequest[ContinueResponse]("continue", args)
  }

  def stepIn(args: StepInArguments): Future[Unit] = {
    sendRequest[JsonElement]("stepIn", args).map(_ => ())
  }

  def stepOut(args: StepOutArguments): Future[Unit] = {
    sendRequest[JsonElement]("stepOut", args).map(_ => ())
  }

  def stepOver(args: NextArguments): Future[Unit] = {
    sendRequest[JsonElement]("next", args).map(_ => ())
  }

  def pause(args: PauseArguments): Future[Unit] = {
    sendRequest[JsonElement]("pause", args).map(_ => ())
  }

  def terminate(args: TerminateArguments): Future[Unit] = {
    sendRequest[JsonElement]("terminate", args).map(_ => ())
  }

  def disconnect(args: DisconnectArguments): Future[Unit] = {
    sendRequest[JsonElement]("disconnect", args).map(_ => ())
  }

  def getThreads(): Future[ThreadsResponse] = {
    ensureInitialized().flatMap { _ =>
      import scala.jdk.CollectionConverters._
      sendRequest[ThreadsResponse]("threads", Map.empty[String, Any].asJava)
    }
  }

  def getStackTrace(args: StackTraceArguments): Future[StackTraceResponse] = {
    ensureInitialized().flatMap { _ =>
      sendRequest[StackTraceResponse]("stackTrace", args)
    }
  }

  def getScopes(args: ScopesArguments): Future[ScopesResponse] = {
    ensureInitialized().flatMap { _ =>
      sendRequest[ScopesResponse]("scopes", args)
    }
  }

  def getVariables(args: VariablesArguments): Future[VariablesResponse] = {
    ensureInitialized().flatMap { _ =>
      sendRequest[VariablesResponse]("variables", args)
    }
  }

  def setBreakpoints(
      args: SetBreakpointsArguments
  ): Future[SetBreakpointsResponse] = {
    scribe.info(
      s"[McpDebugSession] setBreakpoints called for source: ${Option(args.getSource).map(_.getPath).getOrElse("null")}"
    )
    scribe.info(
      s"[McpDebugSession] Breakpoints count: ${Option(args.getBreakpoints).map(_.length).getOrElse(0)}"
    )
    ensureInitialized().flatMap { _ =>
      sendRequest[SetBreakpointsResponse]("setBreakpoints", args)
    }
  }

  def setExceptionBreakpoints(
      args: SetExceptionBreakpointsArguments
  ): Future[SetExceptionBreakpointsResponse] = {
    sendRequest[SetExceptionBreakpointsResponse](
      "setExceptionBreakpoints",
      args,
    )
  }

  def evaluate(args: EvaluateArguments): Future[EvaluateResponse] = {
    sendRequest[EvaluateResponse]("evaluate", args)
  }

  def completions(args: CompletionsArguments): Future[CompletionsResponse] = {
    sendRequest[CompletionsResponse]("completions", args)
  }

  /**
   * Initialize the debug session with proper DAP handshake.
   * This must be called before any other debug operations.
   */
  def initialize(): Future[Unit] = {
    scribe.info(
      s"[McpDebugSession] Starting minimal DAP initialization for session: $sessionId"
    )

    // For MCP debug sessions, the debug server is already running and initialized
    // We only need to send the initialize request to establish the DAP protocol
    scribe.info(s"[McpDebugSession] Sending initialize request")
    val initArgs = new InitializeRequestArguments()
    initArgs.setClientID("mcp-debug-client")
    initArgs.setClientName("MCP Debug Client")
    initArgs.setAdapterID("scala-debug-adapter")
    initArgs.setLocale("en-US")
    initArgs.setLinesStartAt1(true)
    initArgs.setColumnsStartAt1(true)
    initArgs.setPathFormat("uri")  // Use URI format to match what we're sending
    scribe.info(
      s"[McpDebugSession] InitializeRequestArguments created with pathFormat=uri"
    )

    for {
      // Step 1: Send initialize request
      _ <- sendRequest[Capabilities]("initialize", initArgs)

      // Step 2: Send launch or attach request based on session type
      _ <- {
        import scala.jdk.CollectionConverters._
        if (sessionId.contains("attach-remote")) {
          // For attach sessions, send attach request
          scribe.info(s"[McpDebugSession] Detected attach session, sending attach request")
          val attachArgs = Map(
            "type" -> "scala",
            "request" -> "attach",
            "name" -> "MCP Debug Attach",
            "hostName" -> "localhost",
            "port" -> 5005,
            "buildTarget" -> "file:/Users/martin/Workspaces/scala/metals/metals/?id=metals"
          ).asJava
          scribe.info(
            s"[McpDebugSession] Attach arguments: $attachArgs"
          )
          sendRequest[JsonElement]("attach", attachArgs)
        } else {
          // For launch sessions, send launch request
          scribe.info(s"[McpDebugSession] Sending launch request")
          val launchArgs = Map(
            "type" -> "scala",
            "request" -> "launch",
            "name" -> "MCP Debug Launch",
            "noDebug" -> false
          ).asJava
          scribe.info(
            s"[McpDebugSession] Launch arguments: $launchArgs"
          )
          sendRequest[JsonElement]("launch", launchArgs)
        }
      }

      // Step 3: Send configurationDone
      _ <- {
        scribe.info(s"[McpDebugSession] Sending configurationDone")
        import scala.jdk.CollectionConverters._
        sendRequest[JsonElement](
          "configurationDone",
          Map.empty[String, Any].asJava,
        )
      }
    } yield {
      scribe.info(
        s"[McpDebugSession] DAP initialization completed for session: $sessionId"
      )
      ()
    }
  }
}
