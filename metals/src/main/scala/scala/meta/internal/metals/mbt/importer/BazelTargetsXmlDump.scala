package scala.meta.internal.metals.mbt.importer

import scala.xml.XML

class BazelTargetsXmlDump(xmlDump: String) {

  private lazy val root = XML.loadString(xmlDump)

  private lazy val filegroupSrcLabelsByTarget: Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty && isFilegroupRule(rule)
    } yield target -> labelsFromRuleAttribute(rule, Some("srcs")).toList
    targetLabels.toMap
  }

  def getLabels(attributeName: String): Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield {
      val labels = labelsFromRuleAttribute(rule, Some(attributeName)).toList
      val expandedLabels =
        if (attributeName == "srcs") expandFilegroups(labels)
        else labels
      target -> expandedLabels
    }
    targetLabels.toMap
  }

  def getStrings(attributeName: String): Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield target -> stringsFromRuleAttribute(rule, attributeName).toList
    targetLabels.toMap
  }

  lazy val ruleClassesByTarget: Map[String, String] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      ruleClass = (rule \ "@class").text
      if target.nonEmpty && ruleClass.nonEmpty
    } yield target -> ruleClass
    targetLabels.toMap
  }

  lazy val ruleOutputsByTarget: Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield {
      val outputs = for {
        output <- rule \ "rule-output"
        value = (output \ "@name").text
        if value.nonEmpty
      } yield value
      target -> outputs.toList
    }
    targetLabels.toMap
  }

  lazy val depsByTarget: Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield {
      val ruleInputs = for {
        input <- rule \ "rule-input"
        value = (input \ "@name").text
        if value.nonEmpty
      } yield value
      val labels = (ruleInputs ++ labelsFromRuleAttribute(rule, None)).distinct
      target -> labels.toList
    }
    targetLabels.toMap
  }

  lazy val sourceFileLabels: Set[String] = {
    (for {
      sf <- root \\ "source-file"
      name = (sf \ "@name").text
      if name.nonEmpty
    } yield name).toSet
  }

  def externalDepsByTarget(
      reachableLabelsByTarget: Map[String, List[String]]
  ): Map[String, List[String]] =
    reachableLabelsByTarget.map { case (target, deps) =>
      target -> deps.filter(isExternalDep)
    }

  lazy val jarLabelsByImportTarget: Map[String, List[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty && isImportRule(rule)
    } yield target -> labelsFromRuleAttribute(rule, Some("jars")).toList
    targetLabels.toMap
  }

  // These are -sources.jar entries meant for IDEs, not the .srcjar files used for compilation,
  // even though rules_scala calls them "srcjar"
  lazy val sourcesJarByImportTarget: Map[String, Option[String]] = {
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty && isScalaImportRule(rule)
    } yield target -> labelsFromRuleAttribute(rule, Some("srcjar")).headOption
    targetLabels.toMap
  }

  private def isExternalDep(label: String): Boolean =
    label.startsWith("@") && !label.startsWith("@@")

  private def isImportRule(rule: scala.xml.Node): Boolean = {
    val cls = (rule \ "@class").text
    cls == "java_import" || cls == "scala_import"
  }

  private def isScalaImportRule(rule: scala.xml.Node): Boolean =
    (rule \ "@class").text == "scala_import"

  private def isFilegroupRule(rule: scala.xml.Node): Boolean =
    (rule \ "@class").text == "filegroup"

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

  private def labelsFromRuleAttribute(
      rule: scala.xml.Node,
      attributeName: Option[String],
  ): Seq[String] =
    for {
      attribute <- rule \ "_"
      name = (attribute \ "@name").text
      if attributeName.forall(_ == name)
      label <- attribute \\ "label"
      value = (label \ "@value").text
      if value.nonEmpty
    } yield value

  private def stringsFromRuleAttribute(
      rule: scala.xml.Node,
      attributeName: String,
  ): Seq[String] =
    for {
      attribute <- rule \ "_"
      name = (attribute \ "@name").text
      if name == attributeName
      string <- attribute \\ "string"
      value = (string \ "@value").text
      if value.nonEmpty
    } yield value

}
