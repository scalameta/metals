package scala.meta.internal.metals.debug
import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.JvmSignatures
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SourceBreakpoint

private[debug] final class SetBreakpointsRequestHandler(
    classFinder: ClassFinder,
    scalaVersionSelector: ScalaVersionSelector,
) {

  private val previousBreakpointClassNames =
    new TrieMap[AbsolutePath, Set[String]]

  def apply(
      sourcePath: AbsolutePath,
      request: SetBreakpointsArguments,
  ): Iterable[SetBreakpointsArguments] = {
    /* Get symbol for each breakpoint location to figure out the
     * class file that we need to register the breakpoint for.
     */
    val symbols: Array[(SourceBreakpoint, Option[String])] =
      sourcePath.toLanguage match {
        case Language.JAVA =>
          val topLevels = Mtags.allToplevels(
            sourcePath.toInput,
            scalaVersionSelector.getDialect(sourcePath),
          )
          request.getBreakpoints.map { breakpoint =>
            val symbol =
              topLevels.occurrences.minBy(distanceFrom(breakpoint))
            (breakpoint, Option(JvmSignatures.toTypeSignature(symbol).value))
          }
        case _ =>
          request.getBreakpoints.map { breakpoint =>
            val sym =
              classFinder.findClass(sourcePath, breakpoint.toLsp)
            (breakpoint, sym)
          }
      }

    val groups = symbols.groupBy(_._2)
    val partitions = groups.flatMap {
      case (Some(fullyQualifiedClassName), breakpoints) =>
        Some(
          createPartition(
            request,
            fullyQualifiedClassName,
            breakpoints.map(_._1).toArray,
          )
        )
      case (None, nonRegisteredBreakpoints) =>
        val message = nonRegisteredBreakpoints.map { case (br, _) =>
          s"$sourcePath:${br.getLine()}:${br.getColumn()}"
        }
        scribe.debug(s"No class found for $message")
        None
    }

    /* Get previously registered class file names (fully qualified class names) for
     * breakpoints to figure out if we might need to send an empty message to
     * remove breakpoints from one. This is not a problem if we are sending
     * new breakpoints, but it's a problem if breakpoints were removed as
     * partitions will not contain a class file name in case all breakpoints
     * that were pointing to a certain class file were removed.
     */
    val previousClassNames =
      previousBreakpointClassNames.getOrElse(sourcePath, Set.empty[String])
    val currentClassNames = partitions.map(_.getSource().getPath()).toSet
    val classFilesToRemoveBreakpointsFrom =
      previousClassNames.diff(currentClassNames)

    previousBreakpointClassNames += sourcePath -> currentClassNames

    partitions ++ classFilesToRemoveBreakpointsFrom.map(
      createEmptyPartition(request, _)
    )
  }

  private def createEmptyPartition(
      request: SetBreakpointsArguments,
      uri: String,
  ) = {
    val source = DebugProtocol.copy(request.getSource)
    source.setPath(uri)

    val partition = new SetBreakpointsArguments
    partition.setBreakpoints(Array.empty)
    partition.setSource(source)
    partition.setSourceModified(request.getSourceModified)
    partition
  }

  private def createPartition(
      request: SetBreakpointsArguments,
      fqcn: String,
      breakpoints: Array[SourceBreakpoint],
  ) = {
    val source = DebugProtocol.copy(request.getSource)
    source.setPath(s"dap-fqcn:$fqcn")

    val partition = new SetBreakpointsArguments
    partition.setBreakpoints(breakpoints)
    partition.setSource(source)
    partition.setSourceModified(request.getSourceModified)

    partition
  }

  private def distanceFrom(
      breakpoint: SourceBreakpoint
  ): SymbolOccurrence => Long = { occ =>
    val startLine = occ.range.fold(Int.MaxValue)(_.startLine)
    val breakpointLine = breakpoint.toLsp.getLine
    if (startLine > breakpointLine) Long.MaxValue
    else breakpointLine - startLine
  }
}
