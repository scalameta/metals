package scala.meta.metals

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.langmeta.internal.io.FileIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import scala.meta.metals.compiler.CompilerConfig

object Workspace {

  def compilerConfigFiles(cwd: AbsolutePath): Iterable[AbsolutePath] = {
    val configDir = cwd.resolve(RelativePath(CompilerConfig.Directory))
    if (configDir.isDirectory) {
      FileIO.listAllFilesRecursively(configDir)
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
    Option(sbtserver.SbtServer.activeJson(cwd))
      .filter { active => Files.exists(active.toNIO) }
      .foreach(callback)
  }
}
