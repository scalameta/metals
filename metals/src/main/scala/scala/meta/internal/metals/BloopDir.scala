package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object BloopDir {
  def clear(workspace: AbsolutePath): Unit = {
    val bloopDir = workspace.resolve(".bloop")
    bloopDir.list.foreach { f =>
      if (f.exists && f.isDirectory) f.deleteRecursively()
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
