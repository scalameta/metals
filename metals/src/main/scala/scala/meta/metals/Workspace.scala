package scala.meta.metals

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.langmeta.io.AbsolutePath
import scala.meta.metals.compiler.CompilerConfig

object Workspace {

  def compilerConfigFiles(cwd: AbsolutePath): List[AbsolutePath] = {
    import scala.collection.JavaConverters._
    val configDir = cwd.toNIO.resolve(CompilerConfig.Directory)
    if (Files.exists(configDir)) {
      Files.list(configDir).iterator().asScala.map(AbsolutePath.apply).toList
    } else {
      Nil
    }
  }

  def initialize(cwd: AbsolutePath)(
      callback: AbsolutePath => Unit
  ): Unit = {
    compilerConfigFiles(cwd).foreach(callback)
    Files.walkFileTree(
      cwd.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          file match {
            case Semanticdbs.File() =>
              callback(AbsolutePath(file))
            case _ => // ignore, to avoid spamming console.
          }
          FileVisitResult.CONTINUE
        }
      }
    )
  }
}
