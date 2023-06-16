package scala.meta.internal.metals.debug

import scala.meta.internal.metals.BuildTargets
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.debug.SetBreakpointsArguments

/**
 * Note that the scala-debug-adapter source files are either:
 * - a file in a build target SourceItem
 * - a file in a build target DependencySourcesItem
 *
 * If it is a file in a DependencySourcesItem, the [[MetalsDebugAdapter2x]] maps it to
 * its corresponding file in the .metals/readonly/dependencies/ folder.
 */
private[debug] class MetalsDebugAdapter(sourcePathAdapter: SourcePathAdapter) {
  def adaptSetBreakpointsRequest(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments,
  ): Iterable[SetBreakpointsArguments] = {
    // try to find a BSP uri corresponding to the source path or don't send the request
    sourcePathAdapter.toDapURI(sourcePath).map { sourceUri =>
      request.getSource.setPath(sourceUri.toString)
      request
    }
  }

  def adaptStackFrameSource(sourcePath: AbsolutePath): Option[AbsolutePath] = {
    sourcePathAdapter.toMetalsPath(sourcePath)
  }
}

private[debug] object MetalsDebugAdapter {
  def apply(
      buildTargets: BuildTargets,
      targets: Seq[BuildTargetIdentifier],
      supportVirtualDocuments: Boolean,
  ): MetalsDebugAdapter = {
    val sourcePathAdapter =
      SourcePathAdapter(buildTargets, targets, supportVirtualDocuments)
    new MetalsDebugAdapter(sourcePathAdapter)
  }
}
