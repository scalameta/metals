package bench

import java.net.URL
import java.nio.file.Files
import scala.meta.io.AbsolutePath
import tests.BuildInfo

object Corpus {
  def scala(): AbsolutePath = {
    download(
      "scala-sources.zip",
      "https://github.com/scala/scala/archive/v2.12.10.zip"
    )
  }
  def fastparse(): AbsolutePath = {
    download(
      "fastparse-sources.zip",
      "https://github.com/lihaoyi/fastparse/archive/2.1.0.zip"
    )
  }
  def akka(): AbsolutePath = {
    download(
      "akka-sources.zip",
      "https://github.com/akka/akka/archive/v2.5.19.zip"
    )
  }
  private def download(name: String, url: String): AbsolutePath = {
    val zip =
      BuildInfo.targetDirectory.resolve(name)
    Files.createDirectories(zip.toNIO.getParent)
    if (!zip.isFile) {
      val in = new URL(url).openStream()
      try Files.copy(in, zip.toNIO)
      finally in.close()
    }
    zip

  }
}
