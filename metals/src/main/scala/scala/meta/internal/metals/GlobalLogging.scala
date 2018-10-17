package scala.meta.internal.metals

import io.github.soc.directories.ProjectDirectories
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.io.AbsolutePath

object GlobalLogging {
  private val projectDirectories =
    ProjectDirectories.from("org", "scalameta", "metals")
  private val globalDataDir = AbsolutePath(projectDirectories.dataDir)

  /** Returns a printer to trace JSON messages if the user opts into it. */
  def setup(protocolName: String): PrintWriter = {
    MetalsLogger.redirectSystemOut(globalDataDir.resolve("global.log"))
    setupTracePrinter(protocolName)
  }

  def setupTracePrinter(protocolName: String): PrintWriter = {
    val traceFilename = s"${protocolName.toLowerCase}.tracemessages.json"
    val tracemessages = globalDataDir.resolve(traceFilename)
    if (tracemessages.isFile) {
      val fos = Files.newOutputStream(
        tracemessages.toNIO,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING // don't append infinitely to existing file
      )
      new PrintWriter(fos)
    } else {
      scribe.info(
        s"Tracing is disabled for protocol $protocolName, to log every JSON message create '$tracemessages'"
      )
      null
    }

  }

}
