package scala.meta.internal.metals.mbt.importer

import scala.collection.mutable
import scala.meta.internal.semver.SemVer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * Recovers, per target, which source files belong to which Scala version from
 * `bazel query --output=streamed_jsonproto --proto:flatten_selects=false`
 * output (see [[BazelQuery.selectAwareSrcsQuery]]).
 *
 * The `xml` output used by [[BazelQuery.fullInformationQuery]] flattens every
 * `select()` branch into a single `srcs` list, so a target that cross-compiles
 * via `select_for_scala_version` reports source files of Scala versions it is
 * not built with in the default configuration. With
 * `--proto:flatten_selects=false` the proto output keeps the `select()`: each
 * branch is a `SelectorEntry` keyed by a
 * `@rules_scala_config//:scala_version_<x_y_z>` `config_setting` label, letting
 * us tell e.g. Scala 3 sources apart from Scala 2 sources without configuring
 * (analyzing) the build.
 */
object BazelBuildSrcs {

  /** A target's `srcs`, partitioned by the `select()` branch they appear in. */
  case class TargetSrcs(
      unconditional: Set[String],
      byVersion: Map[String, Set[String]],
  ) {

    /** Source labels compiled when the target resolves to `scalaVersion`. */
    def activeFor(scalaVersion: Option[String]): Set[String] =
      unconditional ++ scalaVersion.flatMap(byVersion.get).getOrElse(Set.empty)
  }

  /**
   * Source labels that belong to a `scala_version` `select()` branch that does
   * not match their target's resolved Scala version (and are not compiled by
   * any other target either), each mapped to that branch's Scala version. These
   * are the sources whose version cannot be identified from the default
   * configuration; recovering the branch version lets the caller tag them with
   * their real Scala version (e.g. a Scala 3 source from a
   * `select_for_scala_version(any_3 = ...)` branch) instead of guessing from
   * the project-wide set of versions. When a label appears in several inactive
   * branches the highest version wins. Targets whose `srcs` cannot be parsed
   * simply do not contribute, so they are never misclassified as inactive.
   */
  def inactiveSourceVersions(
      queryOutput: String,
      scalaVersionByTarget: Map[String, Option[String]],
  ): Map[String, String] = {
    val byTarget = parse(queryOutput)
    val active = byTarget.flatMap { case (target, srcs) =>
      srcs.activeFor(scalaVersionByTarget.getOrElse(target, None))
    }.toSet
    val versionsByLabel = mutable.Map.empty[String, mutable.Set[String]]
    for {
      srcs <- byTarget.values
      (version, labels) <- srcs.byVersion
      label <- labels
      if !active.contains(label)
    } versionsByLabel.getOrElseUpdate(label, mutable.Set.empty) += version
    versionsByLabel.flatMap { case (label, versions) =>
      versions.maxByOption(SemVer.Version.fromString).map(label -> _)
    }.toMap
  }

  private val scalaVersionKey = """:scala_version_(\d+_\d+_\d+)""".r

  /**
   * Parses the newline-delimited `Target` JSON, returning each rule's `srcs`
   * partitioned by `select()` branch. One JSON object per line; lines that are
   * blank, not a rule, or otherwise unparseable are skipped.
   */
  def parse(queryOutput: String): Map[String, TargetSrcs] =
    queryOutput.linesIterator
      .filter(_.trim.nonEmpty)
      .flatMap(parseRule)
      .toMap

  private def parseRule(jsonLine: String): Option[(String, TargetSrcs)] =
    Try {
      val target = ujson.read(jsonLine)
      for {
        rule <- target.obj.get("rule")
        name <- rule.obj.get("name").collect { case ujson.Str(n) => n }
      } yield name -> parseSrcs(rule)
    } match {
      case Success(parsed) => parsed
      case Failure(err) =>
        scribe.debug(
          s"bazel-mbt: skipping unparseable query target: ${err.getMessage}"
        )
        None
    }

  private def parseSrcs(rule: ujson.Value): TargetSrcs =
    attributes(rule).find(attributeName(_).contains("srcs")) match {
      case None => TargetSrcs(Set.empty, Map.empty)
      case Some(srcs) =>
        srcs.obj.get("selectorList") match {
          case Some(selectorList) => parseSelectorList(selectorList)
          // No `select()`: a plain label list, compiled for every version.
          case None => TargetSrcs(stringList(srcs).toSet, Map.empty)
        }
    }

  private def parseSelectorList(selectorList: ujson.Value): TargetSrcs = {
    val unconditional = mutable.Set.empty[String]
    val byVersion = mutable.Map.empty[String, mutable.Set[String]]
    // Each element of `selectorList` is one operand of the `+`-concatenation:
    // a literal list or a `select()`. A `scala_version` branch maps to its
    // version; everything else (literal lists, `//conditions:default`, a
    // non-version `config_setting`) is treated as always compiled rather than
    // risk dropping its sources.
    for {
      selector <- arr(selectorList, "elements")
      entry <- arr(selector, "entries")
    } {
      val labels = stringList(entry)
      entryVersion(entry) match {
        case Some(version) =>
          byVersion.getOrElseUpdate(version, mutable.Set.empty) ++= labels
        case None => unconditional ++= labels
      }
    }
    TargetSrcs(
      unconditional.toSet,
      byVersion.map { case (k, v) => k -> v.toSet }.toMap,
    )
  }

  private def entryVersion(entry: ujson.Value): Option[String] =
    entry.obj.get("label").collect { case ujson.Str(l) => l }.flatMap { label =>
      scalaVersionKey.findFirstMatchIn(label).map(_.group(1).replace('_', '.'))
    }

  private def attributes(rule: ujson.Value): List[ujson.Value] =
    arr(rule, "attribute")

  private def attributeName(attribute: ujson.Value): Option[String] =
    attribute.obj.get("name").collect { case ujson.Str(n) => n }

  private def arr(value: ujson.Value, key: String): List[ujson.Value] =
    value.obj.get(key).toList.flatMap(_.arr)

  private def stringList(value: ujson.Value): List[String] =
    arr(value, "stringListValue").collect { case ujson.Str(s) => s }

}
