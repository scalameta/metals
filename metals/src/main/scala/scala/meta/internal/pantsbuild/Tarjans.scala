package scala.meta.internal.pantsbuild

import scala.collection.mutable

// Adapted from:
// https://github.com/indy256/codelibrary/blob/c52247216258e84aac442a23273b7d8306ef757b/java/src/SCCTarjan.java
// https://github.com/lihaoyi/mill/blob/bc71cdb7d3f806ca5eeee2a9782ec6e2e5cf6bff/main/core/src/eval/Tarjans.scala
object Tarjans {
  def fromGraph(edges: Array[Array[Int]]): Seq[Seq[Int]] = {
    val n = edges.length
    val visited = new Array[Boolean](n)
    val onStack = new Array[Boolean](n)
    var stack = List.empty[Int]
    var time = 0
    val index = new Array[Int](n)
    val lowlink = new Array[Int](n)
    val components = mutable.ArrayBuffer.empty[Seq[Int]]

    for (v <- 0 until n) {
      if (!visited(v)) {
        strongconnect(v)
      }
    }

    def strongconnect(v: Int): Unit = {
      index(v) = time
      lowlink(v) = time
      time += 1
      visited(v) = true
      stack ::= v
      onStack(v) = true
      for (w <- edges(v)) {
        if (!visited(w)) {
          strongconnect(w)
          lowlink(v) = math.min(lowlink(v), lowlink(w))
        } else if (onStack(w)) {
          lowlink(v) = math.min(lowlink(v), index(w))
        }
      }
      if (lowlink(v) == index(v)) {
        val component = mutable.Buffer.empty[Int]
        var continue = true
        while (continue) {
          val w = stack.head
          stack = stack.tail
          onStack(w) = false
          component.append(w)
          if (w == v) {
            continue = false
          }
        }
        components.append(component)
      }
    }
    components
  }
}
