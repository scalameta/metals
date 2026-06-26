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
  def isGlob: Boolean = MbtGlobMatcher.isPatternGlob(pattern)

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
  def normalizeSlashes(s: String): String = s.trim.replace('\\', '/')

  def isPatternGlob(pattern: String): Boolean =
    normalizeSlashes(pattern).exists(c =>
      c == '*' || c == '?' || c == '[' || c == '{'
    )

  def fromPattern(raw: String): MbtGlobMatcher = {
    val normalized = normalizeSlashes(raw).stripPrefix("./")
    MbtGlobMatcher(
      pattern = normalized,
      prefix = computePrefix(normalized),
      matcher = FileSystems.getDefault.getPathMatcher("glob:" + normalized),
    )
  }

  private def computePrefix(normalized: String): Option[Path] = {
    val literalSegments = normalized
      .split('/')
      .toSeq
      .filter(_.nonEmpty)
      .takeWhile(segment => !isPatternGlob(segment))
    literalSegments match {
      case head +: tail => Some(Paths.get(head, tail: _*))
      case _ => None
    }
  }
}
