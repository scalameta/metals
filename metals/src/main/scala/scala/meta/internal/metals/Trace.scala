package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.Try

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.logging.LogOnce
import scala.meta.internal.metals.logging.TracingIsEnabled
import scala.meta.io.AbsolutePath

/**
 * Manages JSON-RPC tracing of incoming/outgoing messages via BSP and LSP.
 */
object Trace {
  // jvm-directories can fail for less common OS versions: https://github.com/soc/directories-jvm/issues/17
  def globalDirectory(implicit ec: ExecutionContext): Option[AbsolutePath] =
    Try {
      val projectDirectories =
        MetalsProjectDirectories.from(
          "org",
          "scalameta",
          "metals",
          silent = true,
        ) // we dont want to log anything before redirecting
      // NOTE(olafur): strictly speaking we should use `dataDir` instead of `cacheDir` but on
      // macOS this maps to `$HOME/Library/Application Support` which has an annoying space in
      // the path making it difficult to tail/cat from the terminal and cmd+click from VS Code.
      // Instead, we use the `cacheDir` which has no spaces. The logs are safe to remove so
      // putting them in the "cache directory" makes more sense compared to the "config directory".
      projectDirectories.flatMap { dirs =>
        val cacheDir = Paths.get(dirs.cacheDir)
        // https://github.com/scalameta/metals/issues/5590
        // deal with issue on windows and PowerShell, which would cause us to create a null directory in the workspace
        if (cacheDir.isAbsolute())
          Some(AbsolutePath(cacheDir))
        else None
      }
    }.toOption.flatten

  private val localDirectory: AbsolutePath =
    PathIO.workingDirectory.resolve(".metals/")

  // We default to the global cache here mainly because it's not safe to assume
  // that PathIO.workingDirectory is actually what we want to fallback to.
  // In editors like VS Code that have a "workspace" concept this will
  // normally be fine, but in others like nvim where "workspace" doesn't really
  // exist, we can only rely on the rootUri that is passed in, but we don't have
  // access to that yet when we use this.
  def metalsLog(implicit ec: ExecutionContext): AbsolutePath =
    globalDirectory.getOrElse(localDirectory).resolve("metals.log")

  def protocolTracePath(
      protocolName: String,
      workspace: AbsolutePath = localDirectory,
  ): AbsolutePath = {
    val traceFilename = s"${protocolName.toLowerCase}.trace.json"
    workspace.resolve(traceFilename)
  }

  /**
   * Setups trace printer for a given protocol name and workspace.
   * Searches for trace file in provided workspace. If there is no such file in
   * the workspace, fall backs to the global directory.
   */
  def setupTracePrinter(
      protocolName: String,
      workspace: AbsolutePath = PathIO.workingDirectory,
  )(implicit ec: ExecutionContext): Option[PrintWriter] = {
    val metalsDir = workspace.resolve(".metals")
    val tracePaths = (metalsDir :: globalDirectory.toList).map(dir =>
      protocolTracePath(protocolName, dir)
    )

    @tailrec
    def setupPrintWriter(paths: List[AbsolutePath]): Option[PrintWriter] =
      paths match {
        case head :: _ if head.isFile =>
          LogOnce.info(TracingIsEnabled(head.toString))
          val fos = Files.newOutputStream(
            head.toNIO,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, // don't append infinitely to existing file
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
