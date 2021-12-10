package scala.meta.internal.metals.testProvider

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

final case class TestSuiteDiscoveryResult(
    targetName: String,
    targetUri: String,
    discovered: java.util.List[TestSuiteDiscoveryResult.Discovered]
)

object TestSuiteDiscoveryResult {
  sealed abstract class Discovered(val kind: String) {
    def nonEmpty: Boolean
  }

  final case class Package(
      prefix: String,
      children: java.util.List[Discovered]
  ) extends Discovered("package") {
    def nonEmpty: Boolean = children.asScala.nonEmpty
  }

  final case class TestSuite private (
      fullyQualifiedName: String,
      className: String,
      location: l.Location
  ) extends Discovered("suite") {
    def nonEmpty: Boolean = true
  }
}
