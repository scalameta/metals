package scala.meta.internal.metals.debug

import java.nio.file.Paths

import scala.meta.internal.metals.MetalsEnrichments._
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
    linesStartAt1: Boolean
) {
  // The scala-debug-adapter uses the JVM class file format
  // in which lines start at 1
  def normalizeLineForServer(line: Int): Int = {
    if (linesStartAt1) line
    else line + 1
  }

  def adaptLineForClient(line: Int): Int = {
    if (linesStartAt1) line
    else line - 1
  }

  def toLspPosition(breakpoint: SourceBreakpoint): Position = {
    val line = breakpoint.getLine
    // LSP Position is 0-based
    val lspLine =
      if (linesStartAt1) line - 1
      else line
    new Position(lspLine, breakpoint.getColumn())
  }

  def toMetalsPath(path: String): AbsolutePath = {
    pathFormat match {
      case InitializeRequestArgumentsPathFormat.PATH =>
        Paths.get(path).toUri.toString.toAbsolutePath
      case InitializeRequestArgumentsPathFormat.URI => path.toAbsolutePath
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

  def default: ClientConfigurationAdapter = {
    ClientConfigurationAdapter(defautlPathFormat, defaultLinesStartAt1)
  }

  def initialize(
      initRequest: InitializeRequestArguments
  ): ClientConfigurationAdapter = {
    val pathFormat =
      Option(initRequest.getPathFormat).getOrElse(defautlPathFormat)
    val linesStartAt1 = Option(initRequest.getLinesStartAt1)
      .map(_.booleanValue)
      .getOrElse(defaultLinesStartAt1)
    ClientConfigurationAdapter(pathFormat, linesStartAt1)
  }
}
