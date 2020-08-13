package scala.meta.internal.metals.debug
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JvmSignatures
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolOccurrence

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint

private[debug] final class SetBreakpointsRequestHandler(
    server: ServerAdapter,
    adapters: MetalsDebugAdapters,
    compilers: Compilers
)(implicit ec: ExecutionContext) {

  @volatile private var previousBreakpointUris = Set.empty[String]

  def apply(
      request: SetBreakpointsArguments
  ): Future[SetBreakpointsResponse] = {
    val path =
      adapters.adaptPathForServer(request.getSource.getPath).toAbsolutePath

    val originalSource = DebugProtocol.copy(request.getSource)

    val breakPointSymbols = path.toLanguage match {
      case Language.JAVA =>
        val topLevels = Mtags.allToplevels(path.toInput)
        request.getBreakpoints.map { breakpoint =>
          val symbol = topLevels.occurrences.minBy(distanceFrom(breakpoint))
          Future.successful(
            (breakpoint, Option(JvmSignatures.toTypeSignature(symbol).value))
          )
        }
      case _ =>
        request.getBreakpoints.map { breakpoint =>
          val pos = new Position(breakpoint.getLine(), breakpoint.getColumn())
          compilers.enclosingClass(pos, path).map(sym => (breakpoint, sym))
        }
    }

    Future.sequence(breakPointSymbols.toList).flatMap { symbols =>
      val groups = symbols.groupBy(_._2)
      val partitions = groups.flatMap {
        case (Some(fqcn), breakpoints) =>
          Some(createPartition(request, fqcn, breakpoints.map(_._1).toArray))
        case _ => None
      }

      val allUris = partitions.map(_.getSource().getPath()).toSet
      val removed = previousBreakpointUris.diff(allUris)
      previousBreakpointUris = allUris

      val requests = partitions ++ removed.map { uri =>
        createEmptyPartition(request, uri)
      }

      server
        .sendPartitioned(requests.map(DebugProtocol.syntheticRequest))
        .map(_.map(DebugProtocol.parseResponse[SetBreakpointsResponse]))
        .map(_.flatMap(_.toList))
        .map(assembleResponse(_, originalSource))
    }
  }

  private def assembleResponse(
      responses: Iterable[SetBreakpointsResponse],
      originalSource: Source
  ): SetBreakpointsResponse = {
    val breakpoints = for {
      response <- responses
      breakpoint <- response.getBreakpoints
    } yield {
      breakpoint.setSource(originalSource)
      breakpoint
    }

    val response = new SetBreakpointsResponse
    response.setBreakpoints(breakpoints.toArray)
    response
  }

  private def createEmptyPartition(
      request: SetBreakpointsArguments,
      uri: String
  ) = {
    val source = DebugProtocol.copy(request.getSource)
    source.setPath(uri)

    val partition = new SetBreakpointsArguments
    partition.setBreakpoints(Array.empty)
    partition.setSource(source)
    partition.setLines(Array.empty)
    partition.setSourceModified(request.getSourceModified)
    partition
  }

  private def createPartition(
      request: SetBreakpointsArguments,
      fqcn: String,
      breakpoints: Array[SourceBreakpoint]
  ) = {
    val source = DebugProtocol.copy(request.getSource)
    source.setPath(s"dap-fqcn:$fqcn")

    val lines = breakpoints
      .map(_.getLine: Integer)
      .distinct

    val partition = new SetBreakpointsArguments
    partition.setBreakpoints(breakpoints)
    partition.setSource(source)
    partition.setLines(lines)
    partition.setSourceModified(request.getSourceModified)

    partition
  }

  private def distanceFrom(
      breakpoint: SourceBreakpoint
  ): SymbolOccurrence => Long = { occ =>
    val startLine = occ.range.fold(Int.MaxValue)(_.startLine)
    val breakpointLine = adapters.adaptLine(breakpoint.getLine)
    if (startLine > breakpointLine) Long.MaxValue
    else breakpointLine - startLine
  }
}
