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
import scala.meta.metals.sbtserver.SbtServer

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
      action: AbsolutePath => Unit
  ): Unit = {
    compilerConfigFiles(cwd).foreach(action)

    Files.walkFileTree(
      cwd.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          file match {
            case Semanticdbs.File() =>
              action(AbsolutePath(file))
            case _ => // ignore, to avoid spamming console.
          }
          FileVisitResult.CONTINUE
        }
      }
    )

    action(SbtServer.ActiveJson(cwd))
  }
}
