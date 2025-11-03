package scala.meta.internal.metals.mbt

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

final case class IndexingStats(
    totalFiles: Int = 0,
    updatedFiles: Int = 0,
    backgroundJobs: Future[Unit] = Future.unit,
) {
  // Only use this for testing purposes. In production code, use Future.map
  // instead.
  def awaitBackgroundJobs(
      duration: Duration = Duration("2s")
  ): IndexingStats = {
    Await.result(backgroundJobs, duration)
    this.copy(backgroundJobs = Future.unit)
  }
  def +(other: IndexingStats): IndexingStats = IndexingStats(
    totalFiles + other.totalFiles,
    updatedFiles + other.updatedFiles,
  )
}

object IndexingStats {
  def empty: IndexingStats = IndexingStats()
}
