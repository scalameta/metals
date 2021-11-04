package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

/**
 * Manages JSON-RPC tracing of incoming/outgoing messages via BSP and LSP.
 */
object Trace {
  val metalsLog: AbsolutePath =
    PathIO.workingDirectory.resolve(".metals/metals.log")

  def protocolTracePath(
      protocolName: String,
      workspace: AbsolutePath = PathIO.workingDirectory
  ): AbsolutePath = {
    val traceFilename = s"${protocolName.toLowerCase}.trace.json"
    workspace.resolve(".metals").resolve(traceFilename)
  }

  def setupTracePrinter(
      protocolName: String,
      workspace: AbsolutePath
  ): Option[PrintWriter] = {
    val tracePath = protocolTracePath(protocolName, workspace)
    val path = tracePath.toString()
    if (tracePath.isFile) {
      scribe.info(s"tracing is enabled: $path")
      val fos = Files.newOutputStream(
        tracePath.toNIO,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING // don't append infinitely to existing file
      )
      Some(new PrintWriter(fos))
    } else {
      scribe.info(
        s"tracing is disabled for protocol $protocolName, to enable tracing of incoming " +
          s"and outgoing JSON messages create an empty file at $path"
      )
      None
    }
  }

}
