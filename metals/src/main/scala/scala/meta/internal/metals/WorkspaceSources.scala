package scala.meta.internal.metals

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath
import scala.sys.process._
import scala.util.control.NonFatal

/**
 * Finds sources in the workspace when there is no available build tool.
 */
final class WorkspaceSources(workspace: AbsolutePath) {
  def all: Traversable[AbsolutePath] = {
    if (workspace.extension == "zip" && workspace.isFile) {
      FileIO.withJarFileSystem(workspace, create = false) { root =>
        walkFileTree(root)
      }
    } else if (!workspace.resolve(".git").isDirectory) {
      walkFileTree(workspace)
    } else {
      try {
        gitLsFiles()
      } catch {
        case NonFatal(_) =>
          walkFileTree(workspace)
      }
    }
  }
  def walkFileTree(root: AbsolutePath): Traversable[AbsolutePath] =
    new Traversable[AbsolutePath] {
      override def foreach[U](f: AbsolutePath => U): Unit = {
        Files.walkFileTree(
          root.toNIO,
          new SimpleFileVisitor[Path] {
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              f(AbsolutePath(file))
              FileVisitResult.CONTINUE
            }
            override def preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              val filename = dir.getFileName
              if (filename != null && filename.toString.startsWith(".")) {
                FileVisitResult.SKIP_SUBTREE
              } else {
                FileVisitResult.CONTINUE
              }
            }
          }
        )
      }
    }
  def gitLsFiles(): Traversable[AbsolutePath] = {
    val text = Process(List("git", "ls-files"), cwd = workspace.toFile).!!
    if (text.isEmpty) throw new IllegalArgumentException
    new Traversable[AbsolutePath] {
      override def foreach[U](f: AbsolutePath => U): Unit = {
        text.linesIterator.map(AbsolutePath(_)(workspace)).foreach(f)
      }
    }
  }
}
