package scala.meta.metals.providers

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import scala.meta.AbsolutePath
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import scala.meta.metals.Configuration

class DebugPayloadProvider(
    cwd: AbsolutePath,
    latestConfig: () => Configuration
) extends LazyLogging {
  private def zipWithFiles(f: (String, Array[Byte])*): Array[Byte] = {
    val out = new ByteArrayOutputStream
    val zip = new ZipOutputStream(out)
    f.foreach {
      case (name, bytes) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
    zip.close()
    out.toByteArray
  }

  def generatePayload(): Array[Byte] = {
    zipWithFiles(
      "metals.log" -> cwd.resolve(".metals").resolve("metals.log").readAllBytes,
      "configuration.json" -> latestConfig().asJson.spaces2.toCharArray
        .map(_.toByte)
    )
  }
}
