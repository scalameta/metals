package scala.meta.internal.metals.testProvider

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

final case class TestSuiteDiscoveryResult(
    targetName: String,
    targetUri: String,
    discovered: java.util.List[TestSuiteDiscoveryResult.Discovered]
)

object TestSuiteDiscoveryResult {
  sealed abstract trait Discovered {
    def nonEmpty: Boolean = this match {
      case p: Package => p.children.asScala.nonEmpty
      case _: TestSuite => true
    }
  }

  final case class Package(
      prefix: String,
      children: java.util.List[Discovered]
  ) extends Discovered {
    val kind = "package"
  }

  final case class TestSuite private (
      fullyQualifiedName: String,
      className: String,
      location: l.Location
  ) extends Discovered {
    val kind = "suite",
  }
}
