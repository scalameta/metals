package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta.io.AbsolutePath

object FileWrites {
  def write(destination: AbsolutePath, text: String): Unit = {
    Files.createDirectories(destination.toNIO.getParent)
    Files.write(destination.toNIO, text.getBytes(StandardCharsets.UTF_8))
  }
}
