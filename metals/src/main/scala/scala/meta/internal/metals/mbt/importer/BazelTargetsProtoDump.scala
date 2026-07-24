package scala.meta.internal.metals.mbt.importer

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.TargetSrcs

class BazelTargetsProtoDump(rulesByName: Map[String, BazelRule]) {
  import BazelTargetsProtoDump._

  lazy val isEmpty: Boolean = rulesByName.isEmpty

  lazy val srcsByTarget: Map[String, TargetSrcs] =
    rulesByName.map { case (name, rule) =>
      name -> BazelBuildSrcs.parseSrcs(rule)
    }

  private lazy val filegroupSrcLabelsByTarget: Map[String, List[String]] =
    rulesByName.collect {
      case (name, rule) if rule.ruleClass.contains("filegroup") =>
        name -> rule.flattenedStrings("srcs")
    }

  lazy val filegroupLabels: Set[String] = filegroupSrcLabelsByTarget.keySet

  lazy val srcLabelsByTarget: Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      name -> expandFilegroups(rule.flattenedStrings("srcs"))
    }

  def getStrings(attributeName: String): Map[String, List[String]] =
    rulesByName.map { case (name, rule) =>
      name -> rule.flattenedStrings(attributeName)
    }

  lazy val ruleClassesByTarget: Map[String, String] =
    rulesByName.flatMap { case (name, rule) =>
      rule.ruleClass.map(name -> _)
    }

  lazy val ruleOutputsByTarget: Map[String, List[String]] =
    rulesByName.map { case (name, rule) => name -> rule.ruleOutputs }

  lazy val depsByTarget: Map[String, List[String]] =
    rulesByName.map { case (name, rule) => name -> rule.ruleInputs }

  lazy val jarLabelsByImportTarget: Map[String, List[String]] =
    rulesByName.collect {
      case (name, rule) if rule.ruleClass.exists(isImportRuleClass) =>
        name -> rule.flattenedStrings("jars")
    }

  // These are -sources.jar entries meant for IDEs, not the .srcjar files used
  // for compilation, even though rules_scala calls them "srcjar"
  lazy val sourcesJarByImportTarget: Map[String, Option[String]] =
    rulesByName.collect {
      case (name, rule) if rule.ruleClass.contains("scala_import") =>
        name -> rule.flattenedStrings("srcjar").headOption
    }

  def externalDepsByTarget(
      reachableLabelsByTarget: Map[String, List[String]]
  ): Map[String, List[String]] =
    reachableLabelsByTarget.map { case (target, deps) =>
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

  def reachableLabels(
      rootLabels: List[String]
  ): Map[String, List[String]] =
    rootLabels.map { root =>
      root -> BazelTargetsProtoDump.reachableFrom(root, depsByTarget)
    }.toMap

}

object BazelTargetsProtoDump {

  // Transitive closure of `root` via `adjacency`, BFS order, `root` excluded.
  def reachableFrom(
      root: String,
      adjacency: Map[String, List[String]],
  ): List[String] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    seen += root
    val queue = scala.collection.mutable.Queue(root)
    while (queue.nonEmpty) {
      val current = queue.dequeue()
      for (dep <- adjacency.getOrElse(current, Nil)) {
        if (!seen(dep)) {
          seen += dep
          queue.enqueue(dep)
        }
      }
    }
    seen.toList.filterNot(_ == root)
  }

  // Any repo-qualified label (`@maven//…` or the bzlmod canonical `@@…` form);
  // matching is on the coordinate suffix, so non-Maven labels just fail to
  // match.
  private def isExternalDep(label: String): Boolean =
    label.startsWith("@")

  private def isImportRuleClass(ruleClass: String): Boolean =
    ruleClass == "java_import" || ruleClass == "scala_import"

}
