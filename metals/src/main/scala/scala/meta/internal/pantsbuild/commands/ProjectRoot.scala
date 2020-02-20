package scala.meta.internal.pantsbuild.commands

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

case class ProjectRoot(
    root: AbsolutePath
) {
  val bspRoot: AbsolutePath = root.resolve(root.filename)
  val bspJson: AbsolutePath = bspRoot.resolve(".bsp").resolve("bloop.json")
  val bloopRoot: AbsolutePath = bspRoot.resolve(".bloop")
}
