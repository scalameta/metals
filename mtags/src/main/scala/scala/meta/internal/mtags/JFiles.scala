package scala.meta.internal.mtags

import geny.Generator
import scala.meta.io.AbsolutePath
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

object JFiles {
  def walk(path: AbsolutePath): Generator[AbsolutePath] =
    new Generator[AbsolutePath] {
      def generate(
          handleItem: AbsolutePath => Generator.Action
      ): Generator.Action = {
        var result: Generator.Action = Generator.Continue
        def toResult(action: Generator.Action): FileVisitResult = action match {
          case Generator.Continue => FileVisitResult.CONTINUE
          case Generator.End =>
            result = Generator.End
            FileVisitResult.TERMINATE
        }
        Files.walkFileTree(
          path.toNIO,
          new SimpleFileVisitor[Path] {
            override def preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              toResult(handleItem(AbsolutePath(dir)))
            }
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              toResult(handleItem(AbsolutePath(file)))
            }
          }
        )
        result
      }
    }

  def list(path: AbsolutePath): Generator[AbsolutePath] =
    new Generator[AbsolutePath] {
      def generate(
          handleItem: AbsolutePath => Generator.Action
      ): Generator.Action = {
        val ds = Files.newDirectoryStream(path.toNIO)
        val iter = ds.iterator()
        var currentAction: Generator.Action = Generator.Continue
        try {
          while (iter.hasNext && currentAction == Generator.Continue) {
            currentAction = handleItem(AbsolutePath(iter.next().toAbsolutePath))
          }
        } finally {
          ds.close()
        }
        currentAction
      }
    }
}
