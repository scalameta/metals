package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable
import scala.collection.parallel.mutable.ParArray
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

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
    val command = List("git", "ls-files", "--stage")
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
