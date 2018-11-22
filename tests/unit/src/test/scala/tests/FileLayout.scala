package tests

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.io.AbsolutePath

object FileLayout {
  def fromString(
      layout: String,
      root: AbsolutePath = AbsolutePath(Files.createTempDirectory("scalameta")),
      charset: Charset = StandardCharsets.UTF_8
  ): AbsolutePath = {
    if (!layout.trim.isEmpty) {
      val lines = layout.replaceAllLiterally("\r\n", "\n")
      lines.split("(?=\n/)").foreach { row =>
        row.stripPrefix("\n").split("\n", 2).toList match {
          case path :: contents :: Nil =>
            val file =
              path.stripPrefix("/").split("/").foldLeft(root)(_ resolve _)
            val parent = file.toNIO.getParent
            Files.createDirectories(parent)
            Files.write(
              file.toNIO,
              contents.getBytes(charset),
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE
            )
          case els =>
            throw new IllegalArgumentException(
              s"Unable to split argument info path/contents! \n$els"
            )

        }
      }
    }
    root
  }
}
