package scala.meta.internal.metals.debug

import java.nio.file.Paths
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.{
  InitializeRequestArgumentsPathFormat => PathFormat
}
import scala.meta.io.AbsolutePath

final class MetalsDebugAdapters {
  private var lineAdapter: Long => Long = identity
  private var clientToServerPathAdapter: String => String = identity
  private var serverToClientPathAdapter: AbsolutePath => String =
    p => p.toURI.toString

  def initialize(clientConfig: InitializeRequestArguments): Unit = {
    val shouldAdaptLines =
      Option(clientConfig.getLinesStartAt1).exists(_.booleanValue)

    if (shouldAdaptLines) {
      lineAdapter = line => line - 1 // metals starts at 0
    }

    Option(clientConfig.getPathFormat) match {
      case Some(PathFormat.PATH) =>
        clientToServerPathAdapter = path => Paths.get(path).toUri().toString()
        serverToClientPathAdapter = _.toString
      case Some(PathFormat.URI) =>
        clientToServerPathAdapter = identity // metals expects an URI
        serverToClientPathAdapter = _.toURI.toString
      case _ =>
      // ignore
    }
  }

  def adaptLine(line: Long): Long = lineAdapter(line)

  def adaptPathForServer(path: String): String = clientToServerPathAdapter(path)

  def adaptPathForClient(path: AbsolutePath): String =
    serverToClientPathAdapter(path)
}
