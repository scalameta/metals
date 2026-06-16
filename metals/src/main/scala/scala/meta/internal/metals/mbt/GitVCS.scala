package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable
import scala.collection.parallel.mutable.ParArray
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object GitVCS {

  /**
   * Runs `git status --porcelain --untracked-files=all` and returns a list of
   * absolute paths to the relevant files.
   */
  def status(
      workspace: AbsolutePath,
      isRelevantPath: String => Boolean =
        MbtWorkspaceSymbolProvider.isRelevantPath,
  ): mutable.ArrayBuffer[GitFileStatus] = {
    val result = mutable.ArrayBuffer.empty[GitFileStatus]
    val logger = ProcessLogger { line =>
      line.trim() match {
        case s"${status} ${filepathUntrimmed}" =>
          val filepath = filepathUntrimmed.trim()
          if (isRelevantPath(filepath)) {
            result.append(
              GitFileStatus(
                value = status,
                file = workspace.resolve(filepath),
              )
            )
          }
        case _ =>
      }
    }
    val exitCode = Process(
      List("git", "status", "--porcelain", "--untracked-files=all"),
      cwd = workspace.toFile,
    ).!(logger)
    if (exitCode != 0) {
      scribe.warn(
        s"GitVCS.status failed in workspace $workspace with exit code $exitCode"
      )
    }
    result
  }

  /**
   * Runs `git ls-files --stage` and returns a list of GitBlobs. This command
   * runs within ~200-300ms even for very large repos.
   */
  def lsFilesStage(
      workspace: AbsolutePath,
      isRelevantPath: GitBlob => Boolean = blob =>
        MbtWorkspaceSymbolProvider.isRelevantPath(blob.path),
  ): ParArray[GitBlob] = try {
    lsFilesStageUnsafe(workspace, isRelevantPath)
  } catch {
    case _: GitError =>
      try {
        lsFilesFromDisk(workspace, isRelevantPath)
      } catch {
        case NonFatal(e) =>
          scribe.error(
            s"GitVCS.lsFilesFromDisk failed in workspace $workspace",
            e,
          )
          ParArray.empty[GitBlob]
      }
    case NonFatal(e) =>
      scribe.error(s"GitVCS.lsFilesStage failed in workspace $workspace", e)
      ParArray.empty[GitBlob]
  }

  /**
   * Walks the given directories on disk without any gitignore or hardcoded
   * exclusions. Intended for explicitly listed generated-source directories
   * (e.g. `uncheckedSources` in `mbt.json`) that are gitignored and therefore
   * invisible to `lsFilesStage`.
   */
  def lsFilesFromDirs(
      dirs: Seq[AbsolutePath],
      isRelevantPath: GitBlob => Boolean = blob =>
        MbtWorkspaceSymbolProvider.isRelevantPath(blob.path),
  ): ParArray[GitBlob] = {
    val result = ParArray.newBuilder[GitBlob]
    dirs.foreach { dir =>
      if (Files.isDirectory(dir.toNIO)) {
        Files.walkFileTree(
          dir.toNIO,
          new SimpleFileVisitor[Path] {
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult = {
              val blob = new GitBlob(file.toString, Array.emptyByteArray)
              if (attrs.isRegularFile && isRelevantPath(blob)) {
                try {
                  val oid = OID.fromBlob(Files.readAllBytes(file))
                  blob.oidBytes = oid.getBytes(StandardCharsets.UTF_8)
                  result += blob
                } catch {
                  case NonFatal(_) => // silently ignore unreadable files
                }
              }
              FileVisitResult.CONTINUE
            }
          },
        )
      }
    }
    result.result()
  }

  /**
   * Opens the given srcjar archives and lists source files inside them so the
   * resulting paths are real on-disk paths. Intended for `.srcjar` entries in
   * `uncheckedSources` in `mbt.json`.
   *
   * @param write if true, extract each entry to the workspace dependencies
   *              cache (overwriting any existing file). If false, only return
   *              entries that have already been extracted — useful for
   *              re-indexing without clobbering files written by a previous
   *              extraction step.
   */
  def lsFilesFromSrcJars(
      srcJars: Seq[AbsolutePath],
      workspace: AbsolutePath,
      write: Boolean = true,
      isRelevantPath: GitBlob => Boolean = blob =>
        MbtWorkspaceSymbolProvider.isRelevantPath(blob.path),
  ): ParArray[GitBlob] = {
    val result = ParArray.newBuilder[GitBlob]
    srcJars.foreach { srcJar =>
      try {
        val relPath = workspace.toNIO.relativize(srcJar.toNIO)
        val extractDir = workspace
          .resolve(Directories.dependencies)
          .resolveZipPath(relPath)
        FileIO.withJarFileSystem(srcJar, create = false) { root =>
          root.listRecursive.foreach { path =>
            val blob = new GitBlob(path.toNIO.toString, Array.emptyByteArray)
            if (path.isFile && isRelevantPath(blob)) {
              try {
                val diskPath = extractDir.resolveZipPath(path.toNIO)
                if (write) {
                  Files.createDirectories(diskPath.toNIO.getParent)
                  Files.copy(
                    path.toNIO,
                    diskPath.toNIO,
                    StandardCopyOption.REPLACE_EXISTING,
                  )
                }
                if (diskPath.exists) {
                  val oid = OID.fromBlob(Files.readAllBytes(diskPath.toNIO))
                  result += new GitBlob(
                    diskPath.toString,
                    oid.getBytes(StandardCharsets.UTF_8),
                  )
                }
              } catch {
                case NonFatal(_) =>
              }
            }
          }
        }
      } catch {
        case NonFatal(e) =>
          scribe.warn(s"mbt-v2: failed to read srcjar $srcJar", e)
      }
    }
    result.result()
  }

  private def isIgnoredDirectory(dir: Path): Boolean = {
    dir.endsWith("node_modules") ||
    dir.endsWith(".git") ||
    dir.endsWith(".mvn") ||
    dir.endsWith(".bloop") ||
    dir.endsWith(".gradle") ||
    dir.endsWith(".cache") ||
    dir.endsWith(".history") ||
    dir.endsWith(".DS_Store") ||
    dir.endsWith(".idea") ||
    dir.endsWith("target")
  }

  private def lsFilesFromDisk(
      workspace: AbsolutePath,
      isRelevantPath: GitBlob => Boolean,
  ): ParArray[GitBlob] = {
    val result = ParArray.newBuilder[GitBlob]
    var count = 0
    val maxVisitCount = 150_000
    Files.walkFileTree(
      workspace.toNIO,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult = {
          if (count > maxVisitCount || isIgnoredDirectory(dir)) {
            FileVisitResult.SKIP_SUBTREE
          } else {
            FileVisitResult.CONTINUE
          }
        }
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult = {
          val blob = new GitBlob(file.toString, Array.emptyByteArray)
          if (isRelevantPath(blob)) {
            try {
              val oid = OID.fromBlob(Files.readAllBytes(file))
              blob.oidBytes = oid.getBytes(StandardCharsets.UTF_8)
              count += 1
              result += blob
            } catch {
              case NonFatal(_) => // silently ignore
            }
          }
          FileVisitResult.CONTINUE
        }
      },
    )
    result.result()
  }

  private def lsFilesStageUnsafe(
      workspace: AbsolutePath,
      isRelevantPath: GitBlob => Boolean,
  ): ParArray[GitBlob] = {
    val result = ParArray.newBuilder[GitBlob]
    val logger = ProcessLogger {
      // Format: "mode<space>oid<space>stage<tab>filepath"
      case s"${_ /* mode */} ${oid} ${_ /* stage */}\t${filepath}" =>
        val blob = new GitBlob(filepath, oid.getBytes(StandardCharsets.UTF_8))
        if (isRelevantPath(blob)) {
          result += blob
        }
      case _ => // Ignore other lines, should not happen
    }
    val command = List("git", "ls-files", "--stage", "--recurse-submodules")
    val exitCode = Process(command, cwd = workspace.toFile).!(logger)
    if (exitCode != 0) {
      throw new GitError(workspace, command.mkString(" "), exitCode)
    }
    result.result()
  }

  class GitError(workspace: AbsolutePath, command: String, exitCode: Int)
      extends Exception(
        s"Command '$command' failed in workspace $workspace with exit code $exitCode"
      )

}
