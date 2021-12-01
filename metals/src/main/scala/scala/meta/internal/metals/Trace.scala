package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.annotation.tailrec
import scala.util.Try

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

import dev.dirs.ProjectDirectories

/**
 * Manages JSON-RPC tracing of incoming/outgoing messages via BSP and LSP.
 */
object Trace {
  // jvm-directories can fail for less common OS versions: https://github.com/soc/directories-jvm/issues/17
  val globalDirectory: Option[AbsolutePath] =
    Try {
      val projectDirectories =
        ProjectDirectories.from("org", "scalameta", "metals")
      // NOTE(olafur): strictly speaking we should use `dataDir` instead of `cacheDir` but on
      // macOS this maps to `$HOME/Library/Application Support` which has an annoying space in
      // the path making it difficult to tail/cat from the terminal and cmd+click from VS Code.
      // Instead, we use the `cacheDir` which has no spaces. The logs are safe to remove so
      // putting them in the "cache directory" makes more sense compared to the "config directory".
      AbsolutePath(projectDirectories.cacheDir)
    }.toOption

  val localDirectory: AbsolutePath = PathIO.workingDirectory.resolve(".metals/")
  val metalsLog: AbsolutePath = localDirectory.resolve("metals.log")

  def protocolTracePath(
      protocolName: String,
      workspace: AbsolutePath = localDirectory
  ): AbsolutePath = {
    val traceFilename = s"${protocolName.toLowerCase}.trace.json"
    workspace.resolve(traceFilename)
  }

  /**
   * Setups trace printer for a given protocol name and workspace.
   * Searches for trace file in provided workspace. If there is no such file in the workspace, fall backs to the global directory.
   */
  def setupTracePrinter(
      protocolName: String,
      workspace: AbsolutePath = PathIO.workingDirectory
  ): Option[PrintWriter] = {
    val metalsDir = workspace.resolve(".metals")
    val tracePaths = (metalsDir :: globalDirectory.toList).map(dir =>
      protocolTracePath(protocolName, dir)
    )

    @tailrec
    def setupPrintWriter(paths: List[AbsolutePath]): Option[PrintWriter] =
      paths match {
        case head :: _ if head.isFile =>
          scribe.info(s"tracing is enabled: $head")
          val fos = Files.newOutputStream(
            head.toNIO,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING // don't append infinitely to existing file
          )
          Some(new PrintWriter(fos))
        case _ :: tail =>
          setupPrintWriter(tail)
        case Nil =>
          scribe.info(
            s"tracing is disabled for protocol $protocolName, to enable tracing of incoming " +
              s"and outgoing JSON messages create an empty file at ${tracePaths.mkString(" or ")}"
          )
          None
      }

    setupPrintWriter(tracePaths)
  }
}
