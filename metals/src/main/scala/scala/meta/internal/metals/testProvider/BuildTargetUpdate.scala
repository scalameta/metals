package scala.meta.internal.metals.testProvider

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.mtags

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
    val kind: String,
    val fullyQualifiedClassName: String,
    val className: String
) {
  def fullyQualifiedName: FullyQualifiedName
  def clsName: ClassName
}
object TestExplorerEvent {

  final case class FullyQualifiedName(value: String) extends AnyVal
  final case class ClassName(value: String) extends AnyVal
  final case class RemoveTestSuite(
      @transient fullyQualifiedName: FullyQualifiedName,
      @transient clsName: ClassName
  ) extends TestExplorerEvent(
        "removeSuite",
        fullyQualifiedName.value,
        clsName.value
      )

  final case class AddTestSuite(
      @transient fullyQualifiedName: FullyQualifiedName,
      @transient clsName: ClassName,
      @transient mSymbol: mtags.Symbol,
      location: l.Location,
      canResolveChildren: Boolean = false
  ) extends TestExplorerEvent(
        "addSuite",
        fullyQualifiedName.value,
        clsName.value
      ) {
    val symbol: String = mSymbol.value
    def asRemove: RemoveTestSuite =
      RemoveTestSuite(fullyQualifiedName, clsName)
  }

  final case class AddTestCases(
      @transient fullyQualifiedName: FullyQualifiedName,
      @transient clsName: ClassName,
      testCases: java.util.List[TestCaseEntry]
  ) extends TestExplorerEvent(
        "addTestCases",
        fullyQualifiedName.value,
        clsName.value
      )
}

// Represents a single test within a test suite
final case class TestCaseEntry(
    name: String,
    location: l.Location
)
