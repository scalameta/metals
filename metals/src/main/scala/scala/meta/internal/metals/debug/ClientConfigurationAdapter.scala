package scala.meta.internal.metals.debug

import java.nio.file.Paths

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SourceMapper
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.InitializeRequestArgumentsPathFormat
import org.eclipse.lsp4j.debug.SourceBreakpoint

/**
 * The [[ClientConfigurationAdapter]] uses the client configuration coming from the initialize request
 * to normalize the source path and line numbers.
 *
 * @param pathFormat     either "path" or "uri"
 * @param linesStartAt1  true if client line numbers start at 1
 */
private[debug] final case class ClientConfigurationAdapter(
    clientId: Option[String],
    pathFormat: String,
    val linesStartAt1: Boolean,
    sourceMapper: SourceMapper,
) {
  // The scala-debug-adapter uses the JVM class file format
  // in which lines start at 1
  def normalizeLineForServer(path: AbsolutePath, line: Int): Int = {
    val normalizedLine = if (linesStartAt1) line else line + 1
    sourceMapper.mappedLineForServer(path, normalizedLine)
  }

  def adaptLineForClient(path: AbsolutePath, line: Int): Int = {
    val adaptedLine = if (linesStartAt1) line else line - 1
    sourceMapper.mappedLineForClient(path, adaptedLine)
  }

  /**
   * In the DAP specification, the presentationHint of a StackFrame can be
   * 'normal', 'label' or 'subtle'. Most DAP implementations use 'subtle' to
   * indicate that a frame is skipped by the debugger. The problem is that
   * VSCode does not collapse 'subtle' frames, as other DAP clients do.
   * Instead it collapses 'deemphasize' frames, even if it is not part of the
   * spec.
   *
   * See https://github.com/microsoft/vscode/issues/206801
   */
  def adaptStackTraceResponse(result: JsonObject): JsonObject = {
    if (clientId.contains("vscode")) {
      try {
        // For VSCode only, we hack the json result of the stack trace response
        // to replace all occurrences of 'subtle' by 'deemphasize'.
        val frames = result.get("stackFrames").getAsJsonArray()
        for (i <- 0.until(frames.size)) {
          val frame = frames.get(i).getAsJsonObject()
          val presentationHint = Option(frame.get("presentationHint"))
            .map(_.getAsJsonPrimitive.getAsString)
          if (presentationHint.contains("subtle")) {
            frame.add("presentationHint", new JsonPrimitive("deemphasize"))
          }
        }
      } catch {
        case NonFatal(t) =>
          scribe.error("unexpected error when adapting stack trace response", t)
      }
    }
    result
  }

  def toLspPosition(breakpoint: SourceBreakpoint): Position = {
    val line = breakpoint.getLine
    // LSP Position is 0-based
    val lspLine =
      if (linesStartAt1) line - 1
      else line
    new Position(lspLine, breakpoint.getColumn())
  }

  def toMetalsPath(path: String, mappedFrom: Boolean = false): AbsolutePath = {
    pathFormat match {
      // VS Code normally sends in path, which doesn't encode files from jars properly
      // so URIs are actually sent in this case instead
      case InitializeRequestArgumentsPathFormat.PATH
          if !path.startsWith("file:") && !path.startsWith("jar:") =>
        val uriPath = Paths.get(path).toUri.toString.toAbsolutePath
        val mappedPath =
          if (mappedFrom) sourceMapper.mappedFrom(uriPath)
          else sourceMapper.mappedTo(uriPath)
        mappedPath.getOrElse(uriPath)
      case _ =>
        val absolutePath = path.toAbsolutePath
        val mappedPath =
          if (mappedFrom) sourceMapper.mappedFrom(absolutePath)
          else sourceMapper.mappedTo(absolutePath)
        mappedPath.getOrElse(absolutePath)
    }
  }

  def adaptPathForClient(path: AbsolutePath): String = {
    pathFormat match {
      case InitializeRequestArgumentsPathFormat.PATH =>
        if (path.isJarFileSystem) path.toURI.toString else path.toString
      case InitializeRequestArgumentsPathFormat.URI => path.toURI.toString
    }
  }
}

private[debug] object ClientConfigurationAdapter {
  private val defautlPathFormat = InitializeRequestArgumentsPathFormat.URI
  private val defaultLinesStartAt1 = false

  def default(sourceMapper: SourceMapper): ClientConfigurationAdapter = {
    ClientConfigurationAdapter(
      None,
      defautlPathFormat,
      defaultLinesStartAt1,
      sourceMapper,
    )
  }

  def initialize(
      initRequest: InitializeRequestArguments,
      sourceMapper: SourceMapper,
  ): ClientConfigurationAdapter = {
    val pathFormat =
      Option(initRequest.getPathFormat).getOrElse(defautlPathFormat)
    val linesStartAt1 = Option(initRequest.getLinesStartAt1)
      .map(_.booleanValue)
      .getOrElse(defaultLinesStartAt1)
    ClientConfigurationAdapter(
      Option(initRequest.getClientID),
      pathFormat,
      linesStartAt1,
      sourceMapper,
    )
  }
}
