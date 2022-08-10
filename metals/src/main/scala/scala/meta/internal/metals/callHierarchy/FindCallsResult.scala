package scala.meta.internal.metals.callHierarchy

import org.eclipse.lsp4j
import scala.meta.io.AbsolutePath
import scala.meta.internal.semanticdb.TextDocument

case class FindIncomingCallsResult[T](
    canBuildHierarchyItem: CanBuildCallHierarchyItem[T],
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
)

object FindIncomingCallsResult {
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
    }
}

case class FindOutgoingCallsResult[T](
    canBuildHierarchyItem: CanBuildCallHierarchyItem[T],
    definitionNameRange: lsp4j.Range,
    fromRanges: List[lsp4j.Range],
    source: AbsolutePath,
    doc: TextDocument,
)

object FindOutgoingCallsResult {
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
    }
}
