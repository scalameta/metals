package scala.meta.internal.metals.debug

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.parsing.ClassFinder
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.debug.SetBreakpointsArguments

private[debug] sealed trait MetalsDebugAdapter {
  def adaptSetBreakpointsRequest(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments
  ): Iterable[SetBreakpointsArguments]
  def adaptStackFrameSource(
      sourcePath: String,
      sourceName: String
  ): Option[AbsolutePath]
}

/**
 * Metals Debug Adapter for scala-debug-adapter 1.x
 */
private[debug] class MetalsDebugAdapter1x(
    sourcePathProvider: SourcePathProvider,
    handleSetBreakpointsRequest: SetBreakpointsRequestHandler
) extends MetalsDebugAdapter {

  override def adaptSetBreakpointsRequest(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments
  ): Iterable[SetBreakpointsArguments] = {
    handleSetBreakpointsRequest(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments
    )
  }

  override def adaptStackFrameSource(
      sourcePath: String,
      sourceName: String
  ): Option[AbsolutePath] = {
    sourcePathProvider.findPathFor(sourcePath, sourceName)
  }
}

private[debug] object MetalsDebugAdapter {
  def `1.x`(
      definitionProvider: DefinitionProvider,
      buildTargets: BuildTargets,
      classFinder: ClassFinder,
      scalaVersionSelector: ScalaVersionSelector,
      targets: Seq[BuildTargetIdentifier]
  ): MetalsDebugAdapter1x = {
    val sourcePathProvider =
      new SourcePathProvider(definitionProvider, buildTargets, targets.toList)
    val setBreakpointsHandler =
      new SetBreakpointsRequestHandler(classFinder, scalaVersionSelector)
    new MetalsDebugAdapter1x(sourcePathProvider, setBreakpointsHandler)
  }

  def `2.x`(
      buildTargets: BuildTargets,
      targets: Seq[BuildTargetIdentifier],
      supportVirtualDocuments: Boolean
  ): MetalsDebugAdapter2x = {
    val sourcePathAdapter =
      SourcePathAdapter(buildTargets, targets, supportVirtualDocuments)
    new MetalsDebugAdapter2x(sourcePathAdapter)
  }
}

/**
 * Metals Debug Adapter for scala-debug-adapter 2.x
 * It is simpler than the [[MetalsDebugAdapter1x]] because the scala-debug-adapter 2.x
 * is able to map source files to class files and conversely.
 *
 * Note that the scala-debug-adapter source files are either:
 * - a file in a build target SourceItem
 * - a file in a build target DependencySourcesItem
 *
 * If it is a file in a DependencySourcesItem, the [[MetalsDebugAdapter2x]] maps it to
 * its corresponding file in the .metals/readonly/dependencies/ folder.
 */
private[debug] class MetalsDebugAdapter2x(sourcePathAdapter: SourcePathAdapter)
    extends MetalsDebugAdapter {

  override def adaptSetBreakpointsRequest(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments
  ): Iterable[SetBreakpointsArguments] = {
    // try to find a BSP uri corresponding to the source path or don't send the request
    sourcePathAdapter.toDapURI(sourcePath).map { sourceUri =>
      request.getSource.setPath(sourceUri.toString)
      request
    }
  }

  override def adaptStackFrameSource(
      sourcePath: String,
      sourceName: String
  ): Option[AbsolutePath] = {
    sourcePathAdapter.toMetalsPath(sourcePath)
  }
}
