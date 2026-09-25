package scala.meta.internal.metals.mbt

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

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

object MbtGlobMatcher {

  def fromPattern(pattern: String): MbtGlobMatcher = {
    val normalized = globPatternForMatcher(pattern)
    MbtGlobMatcher(
      pattern = pattern,
      prefix = globPrefix(normalized),
      matcher = FileSystems.getDefault.getPathMatcher("glob:" + normalized),
    )
  }

  def isGlob(pattern: String): Boolean = {
    val normalized = normalizeSlashes(pattern)
    normalized.exists(c => c == '*' || c == '?' || c == '[' || c == '{')
  }

  private def normalizeSlashes(s: String): String =
    s.trim.replace('\\', '/')

  /** Leading `./` is stripped so matchers align with workspace-relative paths. */
  private def globPatternForMatcher(pattern: String): String = {
    val normalized = normalizeSlashes(pattern)
    if (normalized.startsWith("./")) normalized.substring(2) else normalized
  }

  private def globPrefix(pattern: String): Option[Path] = {
    val literalSegments = pattern
      .split('/')
      .toSeq
      .filter(_.nonEmpty)
      .takeWhile(segment => !isGlob(segment))
    literalSegments match {
      case head +: tail => Some(Paths.get(head, tail: _*))
      case _ => None
    }
  }
}
