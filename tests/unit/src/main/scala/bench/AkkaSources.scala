package bench

import java.net.URL
import java.nio.file.Files
import scala.meta.io.AbsolutePath
import tests.BuildInfo

object AkkaSources {
  def download(): AbsolutePath = {
    val zip =
      AbsolutePath(BuildInfo.targetDirectory).resolve("akka-sources.zip")
    Files.createDirectories(zip.toNIO.getParent)
    if (!zip.isFile) {
      val in =
        new URL("https://github.com/akka/akka/archive/v2.5.19.zip").openStream()
      try Files.copy(in, zip.toNIO)
      finally in.close()
    }
    zip
  }
}
