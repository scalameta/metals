package scala.meta.internal.metals.mbt

final case class IndexingStats(
    totalFiles: Int = 0,
    updatedFiles: Int = 0,
)

object IndexingStats {
  def empty: IndexingStats = IndexingStats()
}
