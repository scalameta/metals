package scala.meta.internal.metals.mbt

import java.nio.file.Path
import java.nio.file.PathMatcher

case class MbtGlobMatcher(
    pattern: String,
    prefix: Option[Path],
    matcher: PathMatcher,
) {
  def mayContainMatchesIn(relativeDirectory: Path): Boolean =
    prefix match {
      case None => true
      case Some(value) =>
        relativeDirectory.toString.isEmpty ||
        relativeDirectory.startsWith(value) ||
        value.startsWith(relativeDirectory)
    }
}
