package scala.meta.internal.metals.mbt

import java.nio.file.FileSystems

/**
 * Index filter that excludes protobuf version history files.
 *
 * Filters out files matching the glob pattern for version_history proto files,
 * which are typically auto-generated history files that should not be indexed.
 */
object ProtobufVersionHistoryIndexFilter extends MbtIndexFilter {
  private val matcher = FileSystems.getDefault.getPathMatcher(
    "glob:**/version_history/" + "*.proto"
  )

  override def decide(candidate: MbtFileCandidate): MbtIndexDecision = {
    if (matcher.matches(candidate.path.toNIO)) {
      MbtIndexDecision.Skip
    } else {
      MbtIndexDecision.Continue
    }
  }
}
