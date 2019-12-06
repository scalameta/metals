package scala.meta.internal.metals

import org.scalafmt.interfaces.ScalafmtReporter
import java.io.PrintWriter
import java.io.OutputStream
import java.nio.file.Path
import java.io.OutputStreamWriter

/**
 * A Scalafmt reporter that ignores all messages
 */
object EmptyScalafmtReporter extends ScalafmtReporter {
  def error(file: Path, message: String): Unit = ()
  def error(file: Path, e: Throwable): Unit = ()
  def excluded(file: Path): Unit = ()
  def parsedConfig(config: Path, scalafmtVersion: String): Unit = ()
  def downloadOutputStreamWriter(): OutputStreamWriter =
    new OutputStreamWriter(new OutputStream() {
      def write(b: Int): Unit = ()
    })
  def downloadWriter(): PrintWriter =
    new PrintWriter(new OutputStream() {
      def write(b: Int): Unit = ()
    })
}
