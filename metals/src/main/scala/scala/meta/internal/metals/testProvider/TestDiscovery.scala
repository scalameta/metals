package scala.meta.internal.metals.testProvider

import javax.annotation.Nullable

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

final case class TestDiscovery(
    targetName: String,
    targetUri: String,
    discovered: java.util.List[TestDiscovery.Result]
)

object TestDiscovery {
  sealed trait Result {
    def nonEmpty: Boolean = this match {
      case p: Package => p.children.asScala.nonEmpty
      case _: TestSuite => true
    }
  }

  final class Package private (
      val kind: String,
      val prefix: String,
      val children: java.util.List[Result]
  ) extends Result

  object Package {
    def apply(prefix: String, children: java.util.List[Result]) =
      new Package("package", prefix, children)
  }

  final class TestSuite private (
      val kind: String,
      val fullyQualifiedName: String,
      val className: String,
      @Nullable val location: l.Location
  ) extends Result

  object TestSuite {
    def apply(pkg: String, testName: String, @Nullable location: l.Location) =
      new TestSuite("class", pkg, testName, location)
  }
}
