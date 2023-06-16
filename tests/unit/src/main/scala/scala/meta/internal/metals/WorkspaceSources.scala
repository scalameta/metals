package scala.meta.internal.metals

import scala.sys.process._
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

import geny.Generator

/**
 * Finds sources in the workspace when there is no available build tool.
 */
final class WorkspaceSources(workspace: AbsolutePath) {
  def all: Generator[AbsolutePath] = {
    if (workspace.extension == "zip" && workspace.isFile) {
      FileIO.withJarFileSystem(workspace, create = false) { root =>
        root.listRecursive
      }
    } else if (!workspace.resolve(".git").isDirectory) {
      workspace.listRecursive
    } else {
      try {
        gitLsFiles()
      } catch {
        case NonFatal(_) =>
          workspace.listRecursive
      }
    }
  }

  private def gitLsFiles(): Generator[AbsolutePath] = {
    val text = Process(List("git", "ls-files"), cwd = workspace.toFile).!!
    if (text.isEmpty) throw new IllegalArgumentException
    Generator.from(text.linesIterator.map(AbsolutePath(_)(workspace)))
  }
}
