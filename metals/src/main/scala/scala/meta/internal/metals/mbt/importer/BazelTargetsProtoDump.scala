package scala.meta.internal.metals.mbt.importer

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.TargetSrcs

/**
 * Extracts per-target information from `bazel query --output=streamed_jsonproto
 * --proto:flatten_selects=false` output (see [[BazelQuery.fullInformationQuery]]).
 *
 * Replaces the former `xml`-based dump: the `Target` proto carries a superset
 * of the `xml` data (`ruleClass`, `ruleInput`, `ruleOutput`, and every
 * attribute) AND preserves each `srcs` `select()` branch, which `xml` cannot
 * express because it has no `--proto:flatten_selects=false` equivalent.
 *
 * Because `flatten_selects=false`, select-valued attributes arrive unflattened.
 * This class flattens the ones Bazel's `xml` output would union anyway — `srcs`
 * label lists and the `scalacopts`/`javacopts`/`scala_version` strings — while
 * [[srcsByTarget]] keeps the per-branch split for
 * [[BazelBuildSrcs.inactiveSources]].
 */
class BazelTargetsProtoDump(protoDump: String) {
  import BazelTargetsProtoDump._

  private lazy val rulesByName: Map[String, ujson.Value] =
    BazelBuildSrcs.parseRules(protoDump)

  /**
   * No rules parsed from the query output. Distinguishes a genuinely empty
   * scope from a query that produced nothing because it failed or because the
   * Bazel version did not understand `--output=streamed_jsonproto` with the
   * proto flags (the importer uses this to surface the latter instead of
   * silently writing an empty build).
   */
  lazy val isEmpty: Boolean = rulesByName.isEmpty

  /**
   * Each target's `srcs` partitioned by `select()` branch, preserved through
   * `--proto:flatten_selects=false`. Feeds [[BazelBuildSrcs.inactiveSources]],
   * which scopes itself to the import targets via the Scala-version map.
   */
  lazy val srcsByTarget: Map[String, TargetSrcs] =
    rulesByName.map { case (name, rule) =>
      name -> BazelBuildSrcs.parseSrcs(rule)
    }

  private lazy val filegroupSrcLabelsByTarget: Map[String, List[String]] =
    rulesByName.collect {
      case (name, rule) if ruleClassOf(rule).contains("filegroup") =>
        name -> flattenedStrings(rule, "srcs")
    }

  /**
   * Source-providing filegroup targets in the dump. Their `srcs` are inlined
   * into consuming targets by [[getLabels]] (flattening any `select()`), so a
   * select()-aware query must visit the filegroups THEMSELVES to recover which
   * of the inlined sources belong to inactive branches.
   */
  lazy val filegroupLabels: Set[String] = filegroupSrcLabelsByTarget.keySet

  def getLabels(attributeName: String): Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      val labels = flattenedStrings(rule, attributeName)
      val expandedLabels =
        if (attributeName == "srcs") expandFilegroups(labels) else labels
      name -> expandedLabels
    }

  def getStrings(attributeName: String): Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      name -> flattenedStrings(rule, attributeName)
    }

  lazy val ruleClassesByTarget: Map[String, String] =
    rulesByName.flatMap { case (name, rule) =>
      ruleClassOf(rule).map(name -> _)
    }

  lazy val ruleOutputsByTarget: Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      name -> stringArray(rule, "ruleOutput")
    }

  /**
   * Each rule's direct dependency labels. `ruleInput` is Bazel's computed union
   * of every label input (srcs, deps, toolchains), so it already covers what
   * the `xml` dump recovered from `rule-input` plus each label attribute.
   */
  lazy val depsByTarget: Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      name -> stringArray(rule, "ruleInput")
    }

  def externalDepsByTarget(targets: List[String]): Map[String, List[String]] =
    reachableLabels(targets).map { case (target, deps) =>
      target -> deps.filter(isExternalDep)
    }

  private def expandFilegroups(labels: List[String]): List[String] = {
    def expand(label: String, stack: Set[String]): List[String] =
      filegroupSrcLabelsByTarget.get(label) match {
        case Some(srcs) if !stack.contains(label) =>
          srcs.flatMap(src => expand(src, stack + label))
        case _ =>
          List(label)
      }

    labels.flatMap(label => expand(label, Set.empty)).distinct
  }

  private def reachableLabels(
      rootLabels: List[String]
  ): Map[String, List[String]] =
    rootLabels.map { root =>
      root -> reachableLabels(root, depsByTarget).filterNot(_ == root)
    }.toMap

  private def reachableLabels(
      root: String,
      adjacency: Map[String, List[String]],
  ): List[String] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    val queue = scala.collection.mutable.Queue(root)
    while (queue.nonEmpty) {
      val current = queue.dequeue()
      if (!seen(current)) {
        seen += current
        for (dep <- adjacency.getOrElse(current, Nil)) {
          if (!seen(dep)) queue.enqueue(dep)
        }
      }
    }
    seen.toList
  }

}

object BazelTargetsProtoDump {

  private def ruleClassOf(rule: ujson.Value): Option[String] =
    rule.obj.get("ruleClass").collect { case ujson.Str(s) => s }

  private def stringArray(rule: ujson.Value, key: String): List[String] =
    rule.obj.get(key).toList.flatMap(_.arr).collect { case ujson.Str(s) => s }

  private def attributeNamed(
      rule: ujson.Value,
      attributeName: String,
  ): Option[ujson.Value] =
    rule.obj.get("attribute").toList.flatMap(_.arr).find { attribute =>
      attribute.obj
        .get("name")
        .collect { case ujson.Str(n) => n }
        .contains(attributeName)
    }

  /**
   * The string values of an attribute, unioning every `select()` branch — the
   * same flattening Bazel's `xml` output applies. Works for plain `stringValue`
   * / `stringListValue` attributes and for `selectorList` attributes alike, by
   * collecting those value fields wherever they appear under the attribute.
   */
  private def flattenedStrings(
      rule: ujson.Value,
      attributeName: String,
  ): List[String] =
    attributeNamed(rule, attributeName).toList.flatMap(collectStrings)

  // Empty values are dropped: an unset `STRING` attribute (e.g. `scala_version`
  // on a rule that does not declare one) renders as `"stringValue":""`, which
  // the former `xml` dump also skipped (`if value.nonEmpty`). Keeping it would
  // feed "" to `SemVer.Version.fromString`.
  private def collectStrings(value: ujson.Value): List[String] =
    value match {
      case ujson.Obj(fields) =>
        fields.toList.flatMap {
          case ("stringValue", ujson.Str(s)) if s.nonEmpty => List(s)
          case ("stringListValue", ujson.Arr(items)) =>
            items.toList.collect { case ujson.Str(s) if s.nonEmpty => s }
          case (_, nested) => collectStrings(nested)
        }
      case ujson.Arr(items) => items.toList.flatMap(collectStrings)
      case _ => Nil
    }

  // Any repository-qualified label, whether `bazel query` renders it with an
  // apparent repo name (`@maven//...`) or a canonical bzlmod one (the double-@
  // `@@rules_jvm_external++maven+maven//...` form). Both are kept because
  // matching keys on the coordinate target suffix rather than the repository
  // part (see [[BazelMbtImporter.matchExternalDepsToModules]]); non-Maven labels
  // and the per-artifact backing repos simply fail to match a coordinate.
  private def isExternalDep(label: String): Boolean =
    label.startsWith("@")

}
