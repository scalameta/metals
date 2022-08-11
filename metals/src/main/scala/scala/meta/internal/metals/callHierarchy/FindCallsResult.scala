package scala.meta.internal.metals.callHierarchy

import org.eclipse.lsp4j
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.TextDocument

/**
 * Result of incoming calls finder.
 *
 * @param canBuildHierarchyItem The item that makes the call wrapped in CanBuildHierarchyItem.
 * @param definitionNameRange The range that should be selected.
 * @param fromRanges Ranges at which the calls appear.
 */
case class FindIncomingCallsResult[T](
    canBuildHierarchyItem: CanBuildCallHierarchyItem[T],
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
)

object FindIncomingCallsResult {

  /** Aggregate results of incoming calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindIncomingCallsResult[T]]
  ): List[FindIncomingCallsResult[T]] =
    results.groupBy(_.canBuildHierarchyItem).values.toList.map {
      case result @ FindIncomingCallsResult(
            canBuildHierarchyItem,
            definitionNameRange,
            _,
          ) :: _ =>
        FindIncomingCallsResult(
          canBuildHierarchyItem,
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
case class FindOutgoingCallsResult[T](
    canBuildHierarchyItem: CanBuildCallHierarchyItem[T],
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
    source: AbsolutePath,
    doc: TextDocument,
)

object FindOutgoingCallsResult {

  /** Aggregate results of outgoing calls finder by grouping the fromRanges. */
  def group[T](
      results: List[FindOutgoingCallsResult[T]]
  ): List[FindOutgoingCallsResult[T]] =
    results.groupBy(_.canBuildHierarchyItem).values.toList.map {
      case result @ FindOutgoingCallsResult(
            canBuildHierarchyItem,
            definitionNameRange,
            _,
            source,
            doc,
          ) :: _ =>
        FindOutgoingCallsResult(
          canBuildHierarchyItem,
          definitionNameRange,
          result.flatMap(_.fromRanges),
          source,
          doc,
        )
      case _ =>
        throw new IllegalArgumentException("Can't apply group on a empty list.")
    }
}
