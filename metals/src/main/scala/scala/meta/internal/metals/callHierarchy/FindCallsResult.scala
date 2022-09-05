package scala.meta.internal.metals.callHierarchy

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j

private[callHierarchy] sealed trait FindCallsResult[T <: FindCallsResult[T]] {
  def occurence: SymbolOccurrence
  def definitionNameRange: lsp4j.Range
  def fromRanges: List[lsp4j.Range]
  def aggregate(updatedFromRanges: List[lsp4j.Range]): T

  protected def toLsp[T](
      source: AbsolutePath,
      doc: TextDocument,
      visited: Array[String],
      builder: CallHierarchyItemBuilder,
      lspBuilder: (lsp4j.CallHierarchyItem, ju.List[lsp4j.Range]) => T,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[T] =
    occurence match {
      case SymbolOccurrence(_, symbol, _) =>
        builder
          .build(
            source,
            doc,
            occurence,
            definitionNameRange,
            visited :+ symbol,
            token,
          )
          .collect { case Some(item) =>
            lspBuilder(
              item,
              fromRanges.asJava,
            )
          }
    }
}

/**
 * Result of incoming calls finder.
 *
 * @param canBuildHierarchyItem The item that makes the call wrapped in CanBuildHierarchyItem.
 * @param definitionNameRange The range that should be selected.
 * @param fromRanges Ranges at which the calls appear.
 */
private[callHierarchy] case class FindIncomingCallsResult(
    occurence: SymbolOccurrence,
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
) extends FindCallsResult[FindIncomingCallsResult] {

  def aggregate(
      updatedFromRanges: List[lsp4j.Range]
  ): FindIncomingCallsResult =
    this.copy(fromRanges = updatedFromRanges)

  def toLsp(
      source: AbsolutePath,
      doc: TextDocument,
      builder: CallHierarchyItemBuilder,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[lsp4j.CallHierarchyIncomingCall] =
    toLsp(
      source,
      doc,
      visited,
      builder,
      (to, fromRanges) => new lsp4j.CallHierarchyIncomingCall(to, fromRanges),
      token,
    )
  // toLsp(source, doc)(builder, visited, token)()
}

/**
 * Result of incoming calls finder.
 *
 * @param canBuildHierarchyItem The item that is called wrapped in CanBuildHierarchyItem.
 * @param definitionNameRange The range that should be selected.
 * @param fromRanges Ranges at which the item is called.
 * @param source AbsolutePath where the item that is called is defined.
 * @param doc TextDocument where the item that is called is defined.
 */
private[callHierarchy] case class FindOutgoingCallsResult(
    occurence: SymbolOccurrence,
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
    source: AbsolutePath,
    doc: TextDocument,
) extends FindCallsResult[FindOutgoingCallsResult] {

  def aggregate(
      updatedFromRanges: List[lsp4j.Range]
  ): FindOutgoingCallsResult =
    this.copy(fromRanges = updatedFromRanges)

  def toLsp(
      builder: CallHierarchyItemBuilder,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[lsp4j.CallHierarchyOutgoingCall] =
    toLsp(
      source,
      doc,
      visited,
      builder,
      (to, fromRanges) => new lsp4j.CallHierarchyOutgoingCall(to, fromRanges),
      token,
    )
}

private[callHierarchy] object FindCallsResult {
  private def clearRanges(ranges: List[lsp4j.Range]): List[lsp4j.Range] = {
    val distinctedRanges = ranges.toSet
    distinctedRanges
      .filterNot(range => (distinctedRanges - range).exists(_.encloses(range)))
      .toList
  }

  /** Aggregate results of incoming calls finder by grouping the fromRanges. */
  def group[T <: FindCallsResult[T]](
      results: List[T]
  ): List[T] = {
    results
      .groupBy(_.occurence)
      .values
      .collect { case result @ first :: _ =>
        first.aggregate(clearRanges(result.flatMap(_.fromRanges)))
      }
      .toList
  }
}
