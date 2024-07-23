package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object BloopDir {

  private def deleteWithRetry(f: AbsolutePath, retry: Int = 3): Unit = try {
    f.deleteRecursively()
  } catch {
    case NonFatal(e) =>
      if (retry > 0) deleteWithRetry(f, retry - 1)
      else throw e
  }

  def clear(workspace: AbsolutePath): Unit = {
    val bloopDir = workspace.resolve(".bloop")
    bloopDir.list.foreach { f =>
      if (f.exists && f.isDirectory) {
        deleteWithRetry(f)
      }
    }
    val remainingDirs =
      bloopDir.list.filter(f => f.exists && f.isDirectory).toList
    if (remainingDirs.isEmpty) {
      scribe.info(
        "Deleted directories inside .bloop"
      )
    } else {
      val str = remainingDirs.mkString(", ")
      scribe.error(
        s"Couldn't delete directories inside .bloop, remaining: $str"
      )
    }

  }
}
