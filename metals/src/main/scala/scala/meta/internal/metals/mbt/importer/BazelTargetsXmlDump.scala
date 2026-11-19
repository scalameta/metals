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

  def externalDepsByTarget(
      rootLabels: List[String]
  ): Map[String, List[String]] =
    reachableLabelsWhere(rootLabels)(isExternalDep)

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
    reachableLabelsWhere(rootLabels)(_ => true)

  def reachableLabelsWhere(
      rootLabels: List[String]
  )(predicate: String => Boolean): Map[String, List[String]] =
    if (rootLabels.isEmpty) Map.empty
    else {
      val adjacency = depsByTarget
      val nodeIdByLabel = scala.collection.mutable.HashMap.empty[String, Int]
      val depsByNodeId =
        scala.collection.mutable.ArrayBuffer.empty[List[String]]
      val lowLink = scala.collection.mutable.ArrayBuffer.empty[Int]
      val sccByNode = scala.collection.mutable.ArrayBuffer.empty[Int]
      val tarjanStack = scala.collection.mutable.ArrayBuffer.empty[Int]
      val dfsStack =
        scala.collection.mutable.ArrayBuffer
          .empty[BazelTargetsXmlDump.DfsFrame]

      val matchIdByLabel = scala.collection.mutable.HashMap.empty[String, Int]
      val matchedLabels = scala.collection.mutable.ArrayBuffer.empty[String]
      val sccDownstreamBits =
        scala.collection.mutable.ArrayBuffer.empty[java.util.BitSet]

      def matchId(label: String): Int =
        matchIdByLabel.getOrElseUpdate(
          label, {
            val id = matchedLabels.size
            matchedLabels += label
            id
          },
        )

      def allocateNode(label: String, children: List[String]): Int = {
        val id = depsByNodeId.size
        nodeIdByLabel.put(label, id)
        depsByNodeId += children
        lowLink += id
        sccByNode += -1
        tarjanStack += id
        id
      }

      for (root <- rootLabels) {
        if (!nodeIdByLabel.contains(root)) {
          adjacency.get(root) match {
            case Some(rootDeps) =>
              val rootId = allocateNode(root, rootDeps)
              dfsStack += new BazelTargetsXmlDump.DfsFrame(rootId, rootDeps)
              while (dfsStack.nonEmpty) {
                val topIdx = dfsStack.size - 1
                val frame = dfsStack(topIdx)
                frame.remaining match {
                  case dep :: rest =>
                    frame.remaining = rest
                    adjacency.get(dep) match {
                      case Some(depChildren) =>
                        nodeIdByLabel.get(dep) match {
                          case None =>
                            val w = allocateNode(dep, depChildren)
                            dfsStack += new BazelTargetsXmlDump.DfsFrame(
                              w,
                              depChildren,
                            )
                          case Some(w) =>
                            if (sccByNode(w) == -1 && w < lowLink(frame.u)) {
                              lowLink(frame.u) = w
                            }
                        }
                      case None => ()
                    }
                  case Nil =>
                    dfsStack.remove(topIdx)
                    val u = frame.u
                    if (lowLink(u) == u) {
                      val sccId = sccDownstreamBits.size
                      var startIdx = tarjanStack.size - 1
                      while (tarjanStack(startIdx) != u) {
                        startIdx -= 1
                      }
                      var idx = startIdx
                      while (idx < tarjanStack.size) {
                        sccByNode(tarjanStack(idx)) = sccId
                        idx += 1
                      }
                      var sharedRef: java.util.BitSet =
                        BazelTargetsXmlDump.EmptyBitSet
                      var ownedBits: java.util.BitSet = null
                      def ensureOwned(): java.util.BitSet = {
                        if (ownedBits == null) {
                          ownedBits =
                            if (sharedRef.isEmpty) new java.util.BitSet()
                            else
                              sharedRef.clone().asInstanceOf[java.util.BitSet]
                        }
                        ownedBits
                      }
                      idx = startIdx
                      while (idx < tarjanStack.size) {
                        for (dep <- depsByNodeId(tarjanStack(idx))) {
                          val depNodeId = nodeIdByLabel.getOrElse(dep, -1)
                          if (depNodeId >= 0) {
                            val depScc = sccByNode(depNodeId)
                            if (depScc != sccId) {
                              val childBits = sccDownstreamBits(depScc)
                              if (!childBits.isEmpty) {
                                if (ownedBits != null) {
                                  ownedBits.or(childBits)
                                } else if (sharedRef.isEmpty) {
                                  sharedRef = childBits
                                } else if (sharedRef ne childBits) {
                                  ensureOwned().or(childBits)
                                }
                              }
                            }
                          }
                          if (predicate(dep)) {
                            val bitIdx = matchId(dep)
                            if (ownedBits != null) {
                              ownedBits.set(bitIdx)
                            } else if (!sharedRef.get(bitIdx)) {
                              ensureOwned().set(bitIdx)
                            }
                          }
                        }
                        idx += 1
                      }
                      tarjanStack.dropRightInPlace(tarjanStack.size - startIdx)
                      sccDownstreamBits += (
                        if (ownedBits != null) ownedBits else sharedRef
                      )
                    }
                    if (dfsStack.nonEmpty) {
                      val parent = dfsStack(dfsStack.size - 1).u
                      if (lowLink(u) < lowLink(parent)) {
                        lowLink(parent) = lowLink(u)
                      }
                    }
                }
              }
            case None => ()
          }
        }
      }

      if (matchedLabels.isEmpty) {
        rootLabels.map(_ -> Nil).toMap
      } else {
        val listByBitSet =
          scala.collection.mutable.HashMap
            .empty[java.util.BitSet, List[String]]
        def materialize(bits: java.util.BitSet): List[String] =
          if (bits.isEmpty) Nil
          else
            listByBitSet.getOrElseUpdate(
              bits, {
                val builder = List.newBuilder[String]
                var i = bits.nextSetBit(0)
                while (i >= 0) {
                  builder += matchedLabels(i)
                  i = bits.nextSetBit(i + 1)
                }
                builder.result()
              },
            )

        val result = Map.newBuilder[String, List[String]]
        result.sizeHint(rootLabels.size)
        for (root <- rootLabels) {
          val labels = nodeIdByLabel.get(root) match {
            case None => Nil
            case Some(nodeId) =>
              val bits = sccDownstreamBits(sccByNode(nodeId))
              if (bits.isEmpty) Nil
              else {
                val selfMatchId = matchIdByLabel.getOrElse(root, -1)
                val effectiveBits =
                  if (selfMatchId >= 0 && bits.get(selfMatchId)) {
                    val copy = bits.clone().asInstanceOf[java.util.BitSet]
                    copy.clear(selfMatchId)
                    copy
                  } else bits
                materialize(effectiveBits)
              }
          }
          result += (root -> labels)
        }
        result.result()
      }
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

object BazelTargetsXmlDump {
  private val EmptyBitSet = new java.util.BitSet(0)
  private final class DfsFrame(val u: Int, var remaining: List[String])
}
