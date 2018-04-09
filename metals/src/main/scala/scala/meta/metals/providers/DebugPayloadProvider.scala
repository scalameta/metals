package scala.meta.metals.providers

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import scala.meta.AbsolutePath
import com.typesafe.scalalogging.LazyLogging

class DebugPayloadProvider(
    cwd: AbsolutePath
) extends LazyLogging {
  def generatePayload(): Array[Byte] = {
    val out = new ByteArrayOutputStream
    val zip = new ZipOutputStream(out)

    val logFile = cwd.resolve(".metals").resolve("metals.log")
    val logZipEntry = new ZipEntry("metals.log")
    zip.putNextEntry(new ZipEntry("metals.log"))
    zip.write(logFile.readAllBytes)
    zip.closeEntry()

    zip.close()
    out.toByteArray
  }
}
