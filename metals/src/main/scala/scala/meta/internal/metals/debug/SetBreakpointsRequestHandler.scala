package scala.meta.internal.metals.debug
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.ClassPath
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.semanticdb.SymbolOccurrence

private[debug] final class SetBreakpointsRequestHandler(
    server: ServerAdapter,
    adapters: MetalsDebugAdapters
)(implicit ec: ExecutionContext) {

  // TODO the partitioning does not work for cases when the same file has other types defined
  //  which happens to be closer to the breakpoint line than the actual type to which
  //  the breakpoint should belong. For details see [[tests.debug.BreakpointDapSuite]]
  def apply(
      request: SetBreakpointsArguments
  ): Future[SetBreakpointsResponse] = {
    val path = adapters.adaptPath(request.getSource.getPath)
    val input = path.toAbsolutePath.toInput
    val occurrences = Mtags.allToplevels(input).occurrences
    val groups = request.getBreakpoints.groupBy { breakpoint =>
      val definition = occurrences.minBy(distanceFrom(breakpoint))
      ClassPath.toTypeSignature(definition)
    }

    val partitions = groups.map {
      case (fqcn, breakpoints) =>
        createPartition(request, fqcn.value, breakpoints)
    }

    server
      .sendPartitioned(partitions.map(DebugProtocol.syntheticRequest))
      .map(assembleResponse(_, request.getSource))
  }

  private def assembleResponse(
      responses: Iterable[ResponseMessage],
      originalSource: Source
  ): SetBreakpointsResponse = {
    val breakpoints = responses
      .map(DebugProtocol.parseResponse[SetBreakpointsResponse])
      .flatMap(_.toOption)
      .flatMap(_.getBreakpoints)

    breakpoints.foreach { breakpoint =>
      breakpoint.setSource(originalSource)
    }

    val response = new SetBreakpointsResponse
    response.setBreakpoints(breakpoints.toArray)
    response
  }

  private def createPartition(
      request: SetBreakpointsArguments,
      fqcn: String,
      breakpoints: Array[SourceBreakpoint]
  ) = {
    val source = DebugProtocol.copy(request.getSource)
    source.setPath(s"dap-fqcn:$fqcn")

    val lines = breakpoints.map(_.getLine).distinct

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
    math.abs(breakpointLine - startLine)
  }
}
