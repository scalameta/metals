package scala.meta.internal.pantsbuild

import scala.meta.io.AbsolutePath
import scala.collection.mutable
import java.net.URI
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

object PantsConfiguration {

  /**
   * Converts a Pants target into a Bloop BSP target URI.
   *
   * Copy-pasted from https://github.com/scalacenter/bloop/blob/0bb8e1c2750c555f6414165d90f769dd52d105b8/frontend/src/main/scala/bloop/bsp/ProjectUris.scala#L32
   */
  def toBloopBuildTarget(
      projectBaseDir: AbsolutePath,
      id: String
  ): BuildTargetIdentifier = {
    val existingUri = projectBaseDir.toNIO.toUri
    val uri = new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${id}",
      existingUri.getFragment
    )
    new BuildTargetIdentifier(uri.toString())
  }

  /** Returns the nearest enclosing directory of a Pants target */
  def baseDirectory(workspace: AbsolutePath, target: String): AbsolutePath = {
    workspace.resolve(target.substring(0, target.lastIndexOf(':')))
  }

  /**
   * Returns the toplevel directories that enclose all of the target.
   *
   * For example, this method returns the directories `a/src` and `b` given the
   * targets below:
   *
   * - a/src:foo
   * - a/src/inner:bar
   * - b:b
   * - b/inner:c
   */
  def sourceRoots(
      workspace: AbsolutePath,
      pantsTargets: String
  ): List[AbsolutePath] = {
    val parts = pantsTargets.split(" ").map(_.replaceFirst("/?:.*", "")).sorted
    if (parts.isEmpty) return Nil
    val buf = mutable.ListBuffer.empty[String]
    var current = parts(0)
    buf += current
    parts.iterator.drop(1).foreach { target =>
      if (!target.startsWith(current)) {
        current = target
        buf += current
      }
    }
    buf.result().map(workspace.resolve)
  }
}
