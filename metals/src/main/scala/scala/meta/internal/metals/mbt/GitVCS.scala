package scala.meta.internal.metals.mbt

import java.io.IOException
import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

import scala.meta.io.AbsolutePath

object GitVCS {

  /**
   * Runs `git ls-files --stage` and returns a list of GitBlobs. This command
   * within ~200-300ms even for very large repos.
   */
  def lsFilesStage(
      workspace: AbsolutePath,
      isRelevantPath: GitBlob => Boolean,
  ): mutable.ArrayBuffer[GitBlob] = {
    Try(lsFilesStageUnsafe(workspace, isRelevantPath))
      .recover { case e =>
        scribe.error(s"GitVCS.lsFilesStage failed in workspace $workspace", e)
        mutable.ArrayBuffer.empty[GitBlob]
      }
      .getOrElse(
        mutable.ArrayBuffer.empty[GitBlob]
      )
  }

  private def lsFilesStageUnsafe(
      workspace: AbsolutePath,
      isRelevantPath: GitBlob => Boolean,
  ): mutable.ArrayBuffer[GitBlob] = {
    val result = mutable.ArrayBuffer.empty[GitBlob]
    val logger = ProcessLogger {
      // Format: "mode<space>oid<space>stage<tab>filepath"
      case s"${_ /* mode */} ${oid} ${_ /* stage */}\t${filepath}" =>
        val blob = new GitBlob(filepath, oid.getBytes(StandardCharsets.UTF_8))
        if (isRelevantPath(blob)) {
          result.append(blob)
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
    result
  }

}
