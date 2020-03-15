package scala.meta.internal.pantsbuild

import scala.collection.mutable

case class Cycles(
    children: collection.Map[String, List[String]],
    parents: collection.Map[String, String]
) {
  def acyclicDependency(target: String): String =
    parents.getOrElse(target, target)
}
object Cycles {
  def findConnectedComponents(targets: Map[String, PantsTarget]): Cycles = {
    val graph = Graph.fromTargets(targets.values.toIndexedSeq)
    val ccs = Tarjans.fromGraph(graph.graph)
    val children = mutable.Map.empty[String, List[String]]
    val parents = mutable.Map.empty[String, String]
    ccs.foreach { cc =>
      if (cc.lengthCompare(1) > 0) {
        val all = cc.iterator
          .map(graph.rindex)
          .toList
          .sortBy(name => pantsTargetOrder(targets(name)))
        val head = all.head
        val tail = all.tail
        children(head) = tail
        tail.foreach { child => parents(child) = head }
      }
    }
    Cycles(children, parents)
  }

  private def pantsTargetOrder(target: PantsTarget): Int = {
    val isTargetToot = 1 << 30
    val isSource = 1 << 29
    val isLibrary = 1 << 28
    val isJUnitTests = 1 << 27
    var n = 0
    if (target.isTargetRoot) n |= isTargetToot
    if (target.targetType.isSource) n |= isSource
    if (target.pantsTargetType.isScalaOrJavaLibrary) n |= isLibrary
    if (target.pantsTargetType.isJUnitTests) n |= isJUnitTests
    -n
  }
}
