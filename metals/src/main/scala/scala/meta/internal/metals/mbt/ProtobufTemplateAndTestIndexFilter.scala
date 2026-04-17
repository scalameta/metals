package scala.meta.internal.metals.mbt

import java.nio.file.FileSystems

/**
 * Index filter that excludes protobuf template files and test files with intentional errors.
 *
 * Filters out:
 * - Template files (containing "TEMPLATE" in filename) which have placeholder syntax
 * - Test data files in testdata directories that may have intentional syntax errors
 * - Compliance test files that may have non-standard syntax
 */
object ProtobufTemplateAndTestIndexFilter extends MbtIndexFilter {
  private val templateMatcher = FileSystems.getDefault.getPathMatcher(
    "glob:**/*TEMPLATE*.proto"
  )

  private val testdataMatcher = FileSystems.getDefault.getPathMatcher(
    "glob:**/testdata/**/*.proto"
  )

  private val complianceTestMatcher = FileSystems.getDefault.getPathMatcher(
    "glob:**/compliance/**/test_files/**/*.proto"
  )

  override def decide(candidate: MbtFileCandidate): MbtIndexDecision = {
    val path = candidate.path.toNIO
    if (
      templateMatcher.matches(path) ||
      testdataMatcher.matches(path) ||
      complianceTestMatcher.matches(path)
    ) {
      MbtIndexDecision.Skip
    } else {
      MbtIndexDecision.Continue
    }
  }
}
