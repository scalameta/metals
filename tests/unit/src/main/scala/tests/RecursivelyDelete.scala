package tests

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.meta.io.AbsolutePath

object RecursivelyDelete {
  def apply(root: AbsolutePath): Int = {
    if (root.isFile) {
      Files.delete(root.toNIO)
      1
    } else if (root.isDirectory) {
      var count = 0
      Files.walkFileTree(
        root.toNIO,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = {
            count += 1
            Files.delete(file)
            super.visitFile(file, attrs)
          }
          override def postVisitDirectory(
              dir: Path,
              exc: IOException
          ): FileVisitResult = {
            val stream = Files.list(dir)
            if (!stream.iterator().hasNext) {
              Files.delete(dir)
            }
            stream.close()
            super.postVisitDirectory(dir, exc)
          }
        }
      )
      count
    } else {
      0
    }
  }
}
