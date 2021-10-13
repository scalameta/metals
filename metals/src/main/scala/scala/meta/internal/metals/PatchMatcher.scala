package scala.meta.internal.metals

import java.nio.file.FileSystems

import scala.meta.io.AbsolutePath

sealed trait PathMatcher {
  def matches(path: AbsolutePath): Boolean
}

object PathMatcher {

  final case class Nio(pattern: String) extends PathMatcher {
    private val matcher = FileSystems.getDefault().getPathMatcher(pattern)
    def matches(path: AbsolutePath): Boolean = matcher.matches(path.toNIO)
  }

  final case class Regex(regex: String) extends PathMatcher {
    private val pattern = java.util.regex.Pattern.compile(regex)
    def matches(path: AbsolutePath): Boolean =
      pattern.matcher(path.toString).find()
  }
}
