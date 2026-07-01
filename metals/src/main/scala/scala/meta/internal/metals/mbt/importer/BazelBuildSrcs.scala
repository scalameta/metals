package scala.meta.internal.metals.mbt.importer

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.semver.SemVer

/**
 * Recovers, per target, which source files belong to which Scala version from
 * `bazel query --output=streamed_jsonproto --proto:flatten_selects=false`
 * output (see [[BazelQuery.fullInformationQuery]]).
 *
 * The `xml` output — and `streamed_jsonproto` with the default
 * `--proto:flatten_selects=true` — flattens every `select()` branch into a
 * single `srcs` list, so a target that cross-compiles via
 * `select_for_scala_version` reports source files of Scala versions it is not
 * built with in the default configuration. With `--proto:flatten_selects=false`
 * the proto output keeps the `select()`: each branch is a `SelectorEntry` keyed
 * by a `@rules_scala_config//:scala_version_<x_y_z>` `config_setting` label,
 * letting us tell e.g. Scala 3 sources apart from Scala 2 sources without
 * configuring (analyzing) the build. [[BazelTargetsProtoDump]] parses the rest
 * of the same output and reuses [[parseSrcs]] for the select-aware split here.
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
   * The Scala version of the `select()` branch an inactive source came from,
   * together with the target declaring that branch. The origin target is what
   * lets the importer model the configuration in which Bazel would actually
   * compile the source — the origin target built with [[version]] — so the
   * source's namespace can inherit the origin's dependencies.
   */
  case class InactiveSource(version: String, originTarget: String)

  /**
   * Source labels that belong to a `scala_version` `select()` branch that does
   * not match their target's resolved Scala version (and are not compiled by
   * any other target either), each mapped to that branch's Scala version and
   * origin target. These are the sources whose version cannot be identified
   * from the default configuration; recovering the branch version lets the
   * caller tag them with their real Scala version (e.g. a Scala 3 source from
   * a `select_for_scala_version(any_3 = ...)` branch) instead of guessing from
   * the project-wide set of versions. When a label appears in several inactive
   * branches the highest version wins, and among targets declaring that
   * version the lexicographically smallest label, so the result is
   * deterministic. Targets whose `srcs` cannot be parsed simply do not
   * contribute, so they are never misclassified as inactive.
   */
  def inactiveSources(
      queryOutput: String,
      scalaVersionByTarget: Map[String, Option[String]],
  ): Map[String, InactiveSource] =
    inactiveSources(parse(queryOutput), scalaVersionByTarget)

  /**
   * Overload taking the already-parsed `srcs` (from
   * [[BazelTargetsProtoDump.srcsByTarget]]), so the single transitive
   * `deps(set(...))` query need not be parsed twice. The query is transitive,
   * but the inactive-source analysis must only consider the import targets
   * (and their source-providing filegroups) — exactly the keys of
   * `scalaVersionByTarget` — so a transitively reachable target out of import
   * scope never marks a branch source active.
   */
  def inactiveSources(
      srcsByTarget: Map[String, TargetSrcs],
      scalaVersionByTarget: Map[String, Option[String]],
  ): Map[String, InactiveSource] = {
    val byTarget = srcsByTarget.filter { case (target, _) =>
      scalaVersionByTarget.contains(target)
    }
    val active = byTarget.flatMap { case (target, srcs) =>
      srcs.activeFor(scalaVersionByTarget.getOrElse(target, None))
    }.toSet
    val candidatesByLabel =
      mutable.Map.empty[String, mutable.Set[(String, String)]]
    for {
      (target, srcs) <- byTarget
      (version, labels) <- srcs.byVersion
      label <- labels
      if !active.contains(label)
    } candidatesByLabel.getOrElseUpdate(label, mutable.Set.empty) +=
      (version -> target)
    candidatesByLabel.flatMap { case (label, candidates) =>
      candidates
        .map { case (version, _) => version }
        .maxByOption(SemVer.Version.fromString)
        .map { version =>
          val origin = candidates.collect { case (`version`, target) =>
            target
          }.min
          label -> InactiveSource(version, origin)
        }
    }.toMap
  }

  private val scalaVersionKey = """:scala_version_(\d+_\d+_\d+)""".r

  /**
   * Parses the newline-delimited `Target` JSON, returning each rule's `srcs`
   * partitioned by `select()` branch. One JSON object per line; lines that are
   * blank, not a rule, or otherwise unparseable are skipped.
   */
  def parse(queryOutput: String): Map[String, TargetSrcs] =
    parseRules(queryOutput).map { case (name, rule) =>
      name -> parseSrcs(rule)
    }

  /**
   * Parses the newline-delimited `Target` JSON into each rule's name and its
   * `rule` object. One JSON object per line; blank lines, non-rule targets, and
   * unparseable lines are skipped, matching the `--keep_going` tolerance of the
   * query itself. Shared by [[parse]] and [[BazelTargetsProtoDump]] so the
   * per-line parsing lives in exactly one place.
   */
  def parseRules(queryOutput: String): Map[String, ujson.Value] =
    queryOutput.linesIterator
      .filter(_.trim.nonEmpty)
      .flatMap(parseRuleLine)
      .toMap

  private def parseRuleLine(
      jsonLine: String
  ): Option[(String, ujson.Value)] =
    Try {
      val target = ujson.read(jsonLine)
      for {
        rule <- target.obj.get("rule")
        name <- rule.obj.get("name").collect { case ujson.Str(n) => n }
      } yield name -> rule
    } match {
      case Success(parsed) => parsed
      case Failure(err) =>
        // Bazel is expected to emit well-formed JSON, so a parse failure is a
        // bug on our side (a query/format change we did not account for) rather
        // than bad input — warn rather than swallow it at debug level.
        scribe.warn(
          s"bazel-mbt: skipping unparseable query target: ${err.getMessage}"
        )
        None
    }

  def parseSrcs(rule: ujson.Value): TargetSrcs =
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
