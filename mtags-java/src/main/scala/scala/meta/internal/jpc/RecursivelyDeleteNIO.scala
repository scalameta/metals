package scala.meta.internal.jpc

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

// TODO: Replace this with the standard RecursivelyDelete object in `mtags`,
// which we don't have access to here.
object RecursivelyDeleteNIO {
  def apply(root: Path, excludedNames: Set[String] = Set.empty): Int = {
    if (
      Files.isRegularFile(root) && !excludedNames(root.getFileName.toString)
    ) {
      Files.delete(root)
      1
    } else if (Files.isDirectory(root)) {
      var count = 0
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = if (!excludedNames(file.getFileName.toString)) {
            count += 1
            Files.delete(file)
            super.visitFile(file, attrs)
          } else {
            super.visitFile(file, attrs)
          }
          override def postVisitDirectory(
              dir: Path,
              exc: IOException
          ): FileVisitResult = {
            val stream = Files.list(dir)
            try {
              if (!stream.iterator().hasNext) {
                Files.delete(dir)
              }
            } finally {
              stream.close()
            }
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
