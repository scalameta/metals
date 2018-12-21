package scala.meta.internal.metals
// This file contains parts that are derived from
// Scalafmt, in particular FilterMatcher.scala and OsSpecific.scala:
// - https://github.com/scalameta/scalafmt/blob/64bedcc8f6eed7b9912589ae6cdace86bef974b5/scalafmt-core/shared/src/main/scala/org/scalafmt/config/FilterMatcher.scala#L7
// - https://github.com/scalameta/scalafmt/blob/08c6798a40188ce69b3287e3026becfbd540a847/scalafmt-core/shared/src/main/scala/org/scalafmt/util/OsSpecific.scala

import scala.util.matching.Regex
import java.io.File

case class FilterMatcher(include: Regex, exclude: Regex) {
  def matches(input: String): Boolean =
    include.findFirstIn(input).isDefined &&
      exclude.findFirstIn(input).isEmpty
}

object FilterMatcher {
  private def mkRegexp(filters: Seq[String]): Regex =
    filters match {
      case Nil => "$a".r // will never match anything
      case head :: Nil => head.r
      case _ => filters.mkString("(", "|", ")").r
    }

  private def fixSeparatorsInPathPattern(unixSpecificPattern: String): String =
    unixSpecificPattern.replace('/', File.separatorChar)

  def apply(includeFilters: Seq[String], excludeFilters: Seq[String]): FilterMatcher = {
    val includes = includeFilters.map(fixSeparatorsInPathPattern)
    val excludes = excludeFilters.map(fixSeparatorsInPathPattern)
    new FilterMatcher(mkRegexp(includes), mkRegexp(excludes))
  }
}
