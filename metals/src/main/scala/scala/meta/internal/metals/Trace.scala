package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

import dev.dirs.ProjectDirectories

/**
 * Manages JSON-RPC tracing of incoming/outgoing messages via BSP and LSP.
 */
object Trace {

  def protocolTracePath(
      protocolName: String,
      workspace: AbsolutePath = PathIO.workingDirectory
  ): AbsolutePath = {
    val traceFilename = s"${protocolName.toLowerCase}.trace.json"
    globalDirectory.resolve(".metals").resolve(traceFilename)
  }

  def setup(
      protocolName: String,
      workspace: AbsolutePath = PathIO.workingDirectory
  ): Option[PrintWriter] = {
    MetalsLogger.redirectSystemOut(globalLog)
    setupTracePrinter(protocolName, workspace)
  }

  def globalLog: AbsolutePath = globalDirectory.resolve("global.log")

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
