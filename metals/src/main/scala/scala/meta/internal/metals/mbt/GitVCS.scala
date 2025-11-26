package scala.meta.internal.metals.mbt

import java.io.IOException
import java.nio.charset.StandardCharsets

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
        MbtWorkspaceSymbolSearch.isRelevantPath,
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
        MbtWorkspaceSymbolSearch.isRelevantPath(blob.path),
  ): ParArray[GitBlob] = try {
    lsFilesStageUnsafe(workspace, isRelevantPath)
  } catch {
    case NonFatal(e) =>
      scribe.error(s"GitVCS.lsFilesStage failed in workspace $workspace", e)
      ParArray.empty[GitBlob]
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
    val exitCode =
      Process(List("git", "ls-files", "--stage"), cwd = workspace.toFile)
        .!(logger)
    if (exitCode != 0) {
      throw new IOException(
        s"'git ls-files --stage' failed in workspace $workspace with exit code $exitCode"
      )
    }
    result.result()
  }

}
