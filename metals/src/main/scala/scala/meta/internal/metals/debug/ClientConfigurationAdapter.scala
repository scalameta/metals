package scala.meta.internal.metals.debug

import java.nio.file.Paths

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SourceMapper
import scala.meta.io.AbsolutePath

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
    pathFormat: String,
    linesStartAt1: Boolean,
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
      pathFormat,
      linesStartAt1,
      sourceMapper,
    )
  }
}
