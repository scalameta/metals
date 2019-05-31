package example

import java.util.concurrent.CompletableFuture
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

object Main extends CompletableFuture[Int] {
  println(Option(1).map(_.toString()))
  import java.nio.file.Files
  import java.nio.file.Paths
  List(1).map(identity _)
  System.out.println(identity(), identity(), identity())
  Files.readAllBytes(Paths.get(""))
  new SimpleFileVisitor[Path] {
    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      FileVisitResult.CONTINUE
    }
  }
}
