package scala.meta.internal.pantsbuild

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.meta.internal.pantsbuild.commands.OpenOptions
import scala.meta.internal.pantsbuild.commands.Project
import metaconfig.cli.CliApp
import scala.meta.internal.pantsbuild.commands.ExportOptions

/**
 * The command-line argument parser for BloopPants.
 */
case class Export(
    project: Project,
    open: OpenOptions,
    app: CliApp,
    export: ExportOptions = ExportOptions.default,
    isCache: Boolean = false,
    isRegenerate: Boolean = false,
    token: CancelToken = EmptyCancelToken,
    onFilemap: Filemap => Unit = _ => Unit
) {
  def isSources: Boolean = !export.noSources
  def maxFileCount: Int =
    export.maxFileCount
  def isMergeTargetsInSameDirectory: Boolean =
    export.mergeTargetsInSameDirectory
  def root = project.root
  def common = project.common
  def workspace = common.workspace
  def targets = project.targets
  def out = root.bspRoot.toNIO
  def pants: AbsolutePath = AbsolutePath(workspace.resolve("pants"))
}
