package scala.meta.internal.metals.callHierarchy

import org.eclipse.lsp4j
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.concurrent.Future
import scala.meta.pc.CancelToken
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import java.{util => ju}

private[callHierarchy] sealed trait FindCallsResult[T] {
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
    extends FindCallsResult[lsp4j.CallHierarchyIncomingCall] {

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
  ) =
    anonToLSP(source, doc)(builder, visited, token)
}

private[callHierarchy] object FindIncomingCallsResult {

  /** Aggregate results of incoming calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindIncomingCallsResult]
  )(implicit ec: ExecutionContext): List[FindIncomingCallsResult] =
    results.groupBy(_.occurence).values.toList.collect {
      case result @ FindIncomingCallsResult(
            occurence,
            definitionNameRange,
            _,
          ) :: _ =>
        FindIncomingCallsResult(
          occurence,
          definitionNameRange,
          result.flatMap(_.fromRanges),
        )
    }
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
    extends FindCallsResult[lsp4j.CallHierarchyOutgoingCall] {

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
  ) =
    anonToLSP(builder, visited, token)
}

private[callHierarchy] object FindOutgoingCallsResult {

  /** Aggregate results of outgoing calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindOutgoingCallsResult]
  )(implicit ec: ExecutionContext): List[FindOutgoingCallsResult] =
    results.groupBy(_.occurence).values.toList.collect {
      case result @ FindOutgoingCallsResult(
            occurence,
            definitionNameRange,
            _,
            source,
            doc,
          ) :: _ =>
        FindOutgoingCallsResult(
          occurence,
          definitionNameRange,
          result.flatMap(_.fromRanges),
          source,
          doc,
        )
    }
}
