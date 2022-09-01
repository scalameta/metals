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

private[callHierarchy] sealed trait FindCallsResult[T, S <: FindCallsResult[
  T,
  _,
]] {
  def occurence: SymbolOccurrence
  def definitionNameRange: lsp4j.Range
  def fromRanges: List[lsp4j.Range]

  protected def toLSP(
      lspBuilder: (lsp4j.CallHierarchyItem, ju.List[lsp4j.Range]) => T
  )(source: AbsolutePath, doc: TextDocument)(
      builder: CallHierarchyItemBuilder,
      visited: Array[String],
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

  protected def aggregate(updatedFromRanges: List[lsp4j.Range]): S
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
)(implicit ec: ExecutionContext)
    extends FindCallsResult[
      lsp4j.CallHierarchyIncomingCall,
      FindIncomingCallsResult,
    ] {

  override protected def aggregate(
      updatedFromRanges: List[lsp4j.Range]
  ): FindIncomingCallsResult =
    this.copy(fromRanges = updatedFromRanges)

  private val anonToLSP: (AbsolutePath, TextDocument) => (
      CallHierarchyItemBuilder,
      Array[String],
      CancelToken,
  ) => Future[lsp4j.CallHierarchyIncomingCall] =
    toLSP((from, fromRanges) =>
      new lsp4j.CallHierarchyIncomingCall(from, fromRanges)
    ) _

  def toLSP(
      source: AbsolutePath,
      doc: TextDocument,
      builder: CallHierarchyItemBuilder,
      visited: Array[String],
      token: CancelToken,
  ): Future[lsp4j.CallHierarchyIncomingCall] =
    anonToLSP(source, doc)(builder, visited, token)
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
)(implicit ec: ExecutionContext)
    extends FindCallsResult[
      lsp4j.CallHierarchyOutgoingCall,
      FindOutgoingCallsResult,
    ] {

  override protected def aggregate(
      updatedFromRanges: List[lsp4j.Range]
  ): FindOutgoingCallsResult =
    this.copy(fromRanges = updatedFromRanges)

  private val anonToLSP
      : (CallHierarchyItemBuilder, Array[String], CancelToken) => Future[
        lsp4j.CallHierarchyOutgoingCall
      ] =
    toLSP((to, fromRanges) =>
      new lsp4j.CallHierarchyOutgoingCall(to, fromRanges)
    )(source, doc)

  def toLSP(
      builder: CallHierarchyItemBuilder,
      visited: Array[String],
      token: CancelToken,
  ): Future[lsp4j.CallHierarchyOutgoingCall] =
    anonToLSP(builder, visited, token)
}

private[callHierarchy] object FindCallsResult {
  private def clearRanges(ranges: List[lsp4j.Range]): List[lsp4j.Range] = {
    val distinctedRanges = ranges.toSet
    distinctedRanges
      .filterNot(range => (distinctedRanges - range).exists(_.encloses(range)))
      .toList
  }

  /** Aggregate results of incoming calls finder by grouping the fromRanges. */
  def group[T <: FindCallsResult[_, T]](
      results: List[T]
  ): List[T] =
    results.groupBy(_.occurence).values.toList.collect {
      case result @ first :: _ =>
        first.aggregate(clearRanges(result.flatMap(_.fromRanges)))
    }
}
