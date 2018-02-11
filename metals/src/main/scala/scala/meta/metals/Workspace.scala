package scala.meta.metals

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath

object Workspace {
  def initialize(cwd: AbsolutePath)(
      callback: AbsolutePath => Unit
  ): Unit = {
    Files.walkFileTree(
      cwd.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          PathIO.extension(file) match {
            case "semanticdb" | "compilerconfig" =>
              callback(AbsolutePath(file))
            case _ => // ignore, to avoid spamming console.
          }
          FileVisitResult.CONTINUE
        }
      }
    )
  }
}
