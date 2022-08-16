package scala.meta.internal.metals.callHierarchy

import org.eclipse.lsp4j
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence

/**
 * Result of incoming calls finder.
 *
 * @param canBuildHierarchyItem The item that makes the call wrapped in CanBuildHierarchyItem.
 * @param definitionNameRange The range that should be selected.
 * @param fromRanges Ranges at which the calls appear.
 */
case class FindIncomingCallsResult(
    occurence: SymbolOccurrence,
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
)

object FindIncomingCallsResult {

  /** Aggregate results of incoming calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindIncomingCallsResult]
  ): List[FindIncomingCallsResult] =
    results.groupBy(_.occurence).values.toList.map {
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
      case _ =>
        throw new IllegalArgumentException("Can't apply group on a empty list.")
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
case class FindOutgoingCallsResult(
    occurence: SymbolOccurrence,
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
    source: AbsolutePath,
    doc: TextDocument,
)

object FindOutgoingCallsResult {

  /** Aggregate results of outgoing calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindOutgoingCallsResult]
  ): List[FindOutgoingCallsResult] =
    results.groupBy(_.occurence).values.toList.map {
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
      case _ =>
        throw new IllegalArgumentException("Can't apply group on a empty list.")
    }
}
