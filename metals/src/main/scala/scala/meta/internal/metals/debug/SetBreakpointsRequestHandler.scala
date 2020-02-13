package scala.meta.internal.metals.debug
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.JvmSignatures
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence

private[debug] final class SetBreakpointsRequestHandler(
    server: ServerAdapter,
    adapters: MetalsDebugAdapters
)(implicit ec: ExecutionContext) {

  // TODO: https://github.com/scalameta/metals/issues/1195
  def apply(
      request: SetBreakpointsArguments
  ): Future[SetBreakpointsResponse] = {
    val path =
      adapters.adaptPathForServer(request.getSource.getPath).toAbsolutePath

    val topLevels = Mtags.allToplevels(path.toInput)
    val occurrences = path.toLanguage match {
      case Language.JAVA =>
        def isTypeSymbol(symbol: SymbolInformation): Boolean = {
          val kind = symbol.kind
          kind.isClass || kind.isInterface // enum is of `class` kind
        }

        // make sure only type symbols are under consideration,
        // as static methods are also included in top-levels
        val isType = topLevels.symbols.filter(isTypeSymbol).map(_.symbol).toSet
        topLevels.occurrences.filter(occ => isType(occ.symbol))
      case _ =>
        topLevels.occurrences
    }

    val groups = request.getBreakpoints.groupBy { breakpoint =>
      val definition = occurrences.minBy(distanceFrom(breakpoint))
      JvmSignatures.toTypeSignature(definition)
    }

    val partitions = groups.map {
      case (fqcn, breakpoints) =>
        createPartition(request, fqcn.value, breakpoints)
    }

    server
      .sendPartitioned(partitions.map(DebugProtocol.syntheticRequest))
      .map(_.map(DebugProtocol.parseResponse[SetBreakpointsResponse]))
      .map(_.flatMap(_.toList))
      .map(assembleResponse(_, request.getSource))
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
    if (startLine > breakpointLine) Long.MaxValue
    else breakpointLine - startLine
  }
}
