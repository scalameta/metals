package tests

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object RecursivelyDeleteDirectory {
  def run(root: Path): Unit = {
    if (!Files.exists(root)) return
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
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
  }
}
