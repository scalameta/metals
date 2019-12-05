package scala.meta.internal.pantsbuild

import ujson.Value
import scala.collection.mutable

case class Cycles(
    children: collection.Map[String, List[String]],
    parents: collection.Map[String, String]
) {
  def acyclicDependency(target: String): String =
    parents.getOrElse(target, target)
}
object Cycles {
  def findConnectedComponents(js: Value): Cycles = {
    val graph = Graph.fromExport(js)
    val ccs = Tarjans.fromGraph(graph.graph)
    val children = mutable.Map.empty[String, List[String]]
    val parents = mutable.Map.empty[String, String]
    ccs.foreach { cc =>
      if (cc.lengthCompare(1) > 0) {
        val it = cc.iterator.map(graph.rindex)
        val head = it.next()
        val tail = it.toList
        children(head) = tail
        tail.foreach { child =>
          parents(child) = head
        }
      }
    }
    Cycles(children, parents)
  }
}
