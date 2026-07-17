package scala.meta.internal.metals.mbt.importer

import java.io.ByteArrayInputStream

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.metals.mbt.importer.bazelproto.BazelQueryProto.Attribute
import scala.meta.internal.metals.mbt.importer.bazelproto.BazelQueryProto.Attribute.SelectorEntry
import scala.meta.internal.metals.mbt.importer.bazelproto.BazelQueryProto.Rule
import scala.meta.internal.metals.mbt.importer.bazelproto.BazelQueryProto.Target

object BazelStreamedProto {

  def parseRules(bytes: Array[Byte]): Map[String, BazelRule] = {
    val in = new ByteArrayInputStream(bytes)
    val rules = Map.newBuilder[String, BazelRule]
    try {
      var target = Target.parseDelimitedFrom(in)
      while (target != null) {
        parseTarget(target).foreach(rules += _)
        target = Target.parseDelimitedFrom(in)
      }
    } catch {
      // A malformed message cannot be resynced; keep whatever parsed. An empty
      // result lets the importer surface the failure instead of wiping the build.
      case NonFatal(err) =>
        scribe.warn(
          s"bazel-mbt: failed to parse streamed_proto query output: ${err.getMessage}"
        )
    }
    rules.result()
  }

  private def parseTarget(target: Target): Option[(String, BazelRule)] =
    if (target.hasRule) parseRule(target.getRule) else None

  private def parseRule(rule: Rule): Option[(String, BazelRule)] =
    Option.when(rule.hasName)(
      rule.getName -> BazelRule(
        name = rule.getName,
        ruleClass = Option.when(rule.hasRuleClass)(rule.getRuleClass),
        ruleInputs = rule.getRuleInputList.asScala.toList,
        ruleOutputs = rule.getRuleOutputList.asScala.toList,
        attributes = rule.getAttributeList.asScala.flatMap { attribute =>
          Option.when(attribute.hasName)(
            attribute.getName -> branchesOf(attribute)
          )
        }.toMap,
      )
    )

  private def branchesOf(attribute: Attribute): List[BazelSelectBranch] =
    if (attribute.hasSelectorList) {
      for {
        selector <- attribute.getSelectorList.getElementsList.asScala.toList
        entry <- selector.getEntriesList.asScala.toList
      } yield BazelSelectBranch(
        Option.when(entry.hasLabel)(entry.getLabel),
        entryValues(entry),
      )
    } else {
      val values = attributeValues(attribute)
      if (values.isEmpty) Nil else List(BazelSelectBranch(None, values))
    }

  private def attributeValues(attribute: Attribute): List[String] =
    if (attribute.hasStringValue) List(attribute.getStringValue)
    else attribute.getStringListValueList.asScala.toList

  private def entryValues(entry: SelectorEntry): List[String] =
    if (entry.hasStringValue) List(entry.getStringValue)
    else entry.getStringListValueList.asScala.toList

}
