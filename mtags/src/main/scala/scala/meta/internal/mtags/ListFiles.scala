package scala.meta.internal.mtags

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.meta.io.AbsolutePath

object ListFiles {
  def foreach(root: AbsolutePath)(fn: AbsolutePath => Unit): Unit = {
    if (root.isFile) fn(root)
    else if (root.isDirectory) {
      try {
        Files.walkFileTree(
          root.toNIO,
          new SimpleFileVisitor[Path] {
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              fn(AbsolutePath(file))
              super.visitFile(file, attrs)
            }
          }
        )
      } catch {
        case _: NoSuchFileException =>
          () // error is reported by the JDK
      }
    }
  }
}
