package scala.meta.internal.metals.mbt

import scala.meta.io.AbsolutePath

/**
 * Represents a candidate file being considered for indexing.
 *
 * @param path The absolute path to the file
 * @param relativePath The path relative to the workspace root
 */
case class MbtFileCandidate(
    path: AbsolutePath,
    relativePath: String,
)

/**
 * Decision returned by an index filter.
 *
 * - `Continue`: The file passes this filter, but may still be excluded by other filters.
 * - `Skip`: The file should be excluded from indexing.
 */
sealed trait MbtIndexDecision
object MbtIndexDecision {
  case object Continue extends MbtIndexDecision
  case object Skip extends MbtIndexDecision
}

/**
 * Filter for deciding which files should be indexed.
 *
 * Multiple filters can be applied, and a file is only indexed if ALL filters
 * return `Continue`. A single `Skip` from any filter excludes the file.
 */
trait MbtIndexFilter {
  def decide(candidate: MbtFileCandidate): MbtIndexDecision
}

object MbtIndexFilter {

  /** Default filter that includes all files. */
  val IncludeAll: MbtIndexFilter = _ => MbtIndexDecision.Continue
}
