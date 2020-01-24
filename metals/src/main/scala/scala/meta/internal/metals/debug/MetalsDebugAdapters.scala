package scala.meta.internal.metals.debug

import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.{
  InitializeRequestArgumentsPathFormat => PathFormat
}
import java.nio.file.Paths

final class MetalsDebugAdapters {
  private var lineAdapter: Long => Long = identity
  private var pathAdapter: String => String = identity

  def initialize(clientConfig: InitializeRequestArguments): Unit = {
    val shouldAdaptLines =
      Option(clientConfig.getLinesStartAt1).exists(_.booleanValue)

    if (shouldAdaptLines) {
      lineAdapter = line => line - 1 // metals starts at 0
    }

    Option(clientConfig.getPathFormat) match {
      case Some(PathFormat.PATH) =>
        pathAdapter = path => Paths.get(path).toUri().toString()
      case Some(PathFormat.URI) =>
        pathAdapter = identity // metals expects an URI
      case _ =>
      // ignore
    }
  }

  def adaptLine(line: Long): Long = lineAdapter(line)

  def adaptPath(path: String): String = pathAdapter(path)
}
