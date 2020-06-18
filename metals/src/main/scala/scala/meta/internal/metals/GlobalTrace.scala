package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

import io.github.soc.directories.ProjectDirectories

/**
 * Manages JSON-RPC tracing of incoming/outgoing messages via BSP and LSP.
 */
object GlobalTrace {

  /**
   * Returns a printer to trace JSON messages if the user opts into it. */
  def setup(protocolName: String): PrintWriter = {
    MetalsLogger.redirectSystemOut(globalLog)
    setupTracePrinter(protocolName)
  }

  def globalLog: AbsolutePath = globalDirectory.resolve("global.log")

  def protocolTracePath(protocolName: String): AbsolutePath = {
    val traceFilename = s"${protocolName.toLowerCase}.trace.json"
    globalDirectory.resolve(traceFilename)
  }

  def setupTracePrinter(protocolName: String): PrintWriter = {
    val tracePath = protocolTracePath(protocolName)
    val path = tracePath.toString()
    if (tracePath.isFile) {
      scribe.info(s"tracing is enabled: $path")
      val fos = Files.newOutputStream(
        tracePath.toNIO,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING // don't append infinitely to existing file
      )
      new PrintWriter(fos)
    } else {
      scribe.info(
        s"tracing is disabled for protocol $protocolName, to enable tracing of incoming " +
          s"and outgoing JSON messages create an empty file at $path"
      )
      null
    }
  }

  def globalDirectory: AbsolutePath = {
    try {
      val projectDirectories =
        ProjectDirectories.from("org", "scalameta", "metals")
      // NOTE(olafur): strictly speaking we should use `dataDir` instead of `cacheDir` but on
      // macOS this maps to `$HOME/Library/Application Support` which has an annoying space in
      // the path making it difficult to tail/cat from the terminal and cmd+click from VS Code.
      // Instead, we use the `cacheDir` which has no spaces. The logs are safe to remove so
      // putting them in the "cache directory" makes more sense compared to the "config directory".
      AbsolutePath(projectDirectories.cacheDir)
    } catch {
      case NonFatal(_) =>
        // jvm-directories can fail for less common OS versions: https://github.com/soc/directories-jvm/issues/17
        // Fall back to the working directory instead of crashing the entire server.
        val cwd = PathIO.workingDirectory.resolve(".metals")
        Files.createDirectories(cwd.toNIO)
        cwd
    }
  }

}
