package tests

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.io.AbsolutePath

object FileLayout {

  def mapFromString(layout: String): Map[String, String] = {
    if (!layout.trim.isEmpty) {
      val lines = layout.replaceAllLiterally("\r\n", "\n")
      lines
        .split("(?=\n/)")
        .map { row =>
          row.stripPrefix("\n").split("\n", 2).toList match {
            case path :: contents :: Nil =>
              path.stripPrefix("/") -> contents
            case els =>
              throw new IllegalArgumentException(
                s"Unable to split argument info path/contents! \n$els"
              )
          }
        }
        .toMap
    } else {
      Map.empty
    }
  }

  def fromString(
      layout: String,
      root: AbsolutePath = AbsolutePath(Files.createTempDirectory("scalameta")),
      charset: Charset = StandardCharsets.UTF_8
  ): AbsolutePath = {
    if (!layout.trim.isEmpty) {
      mapFromString(layout).foreach {
        case (path, contents) =>
          val file =
            path.split("/").foldLeft(root)(_ resolve _)
          val parent = file.toNIO.getParent
          if (!Files.exists(parent)) { // cannot create directories when parent is a symlink
            Files.createDirectories(parent)
          }
          Files.deleteIfExists(file.toNIO)
          Files.write(
            file.toNIO,
            contents.getBytes(charset),
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE
          )
      }
    }
    root
  }
}
