package scala.meta.internal.metals.testProvider

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.BuildTarget
import org.eclipse.{lsp4j => l}
final case class BuildTargetUpdate(
    targetName: String,
    targetUri: String,
    events: java.util.List[TestExplorerEvent]
)
object BuildTargetUpdate {
  def apply(
      buildTarget: BuildTarget,
      events: Seq[TestExplorerEvent]
  ): BuildTargetUpdate =
    BuildTargetUpdate(
      buildTarget.getDisplayName,
      buildTarget.getId.getUri,
      events.asJava
    )
}

sealed abstract class TestExplorerEvent(
    val kind: String
) {
  def fullyQualifiedClassName: String
  def className: String
}
object TestExplorerEvent {
  final case class RemoveTestSuite(
      fullyQualifiedClassName: String,
      className: String
  ) extends TestExplorerEvent("removeSuite")

  final case class AddTestSuite(
      fullyQualifiedClassName: String,
      className: String,
      symbol: String,
      location: l.Location,
      canResolveChildren: Boolean = false
  ) extends TestExplorerEvent("addSuite") {
    def asRemove: RemoveTestSuite =
      RemoveTestSuite(fullyQualifiedClassName, className)
  }

  final case class AddTestCases(
      fullyQualifiedClassName: String,
      className: String,
      testCases: java.util.List[TestCaseEntry]
  ) extends TestExplorerEvent("addTestCases")
}

// Represents a single test within a test suite
final case class TestCaseEntry(
    name: String,
    location: l.Location
)
