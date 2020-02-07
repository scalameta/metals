package scala.meta.internal.pantsbuild

import ujson.Value
import bloop.config.{Config => C}

case class Graph(
    export: Value,
    index: String => Int,
    rindex: Int => String,
    graph: Array[Array[Int]]
)
object Graph {
  def fromSeq(graph: Seq[Seq[Int]]): Graph = {
    Graph(Value.Str(""), _.toInt, _.toString, graph.map(_.toArray).toArray)
  }
  def fromProjects(projects: Seq[C.Project]): Graph = {
    val edges = new Array[Array[Int]](projects.length)
    val index = projects.map(_.name).zipWithIndex.toMap
    val rindex = index.map(_.swap).toMap
    projects.zipWithIndex.foreach {
      case (project, i) =>
        edges(i) = project.dependencies.iterator.map(index).toArray
    }
    Graph(Value.Str(""), index.apply _, rindex.apply _, edges)
  }
  def fromTargets(targets: IndexedSeq[PantsTarget]): Graph = {
    val edges = new Array[Array[Int]](targets.length)
    val index = targets.map(_.name).zipWithIndex.toMap
    val rindex = index.map(_.swap).toMap
    targets.zipWithIndex.foreach {
      case (project, i) =>
        edges(i) = project.dependencies.iterator.map(index).toArray
    }
    Graph(Value.Str(""), index.apply _, rindex.apply _, edges)
  }
  def fromExport(export: Value): Graph = {
    val targets = export.obj("targets").obj
    val index = targets.keysIterator.zipWithIndex.toMap
    val rindex = index.iterator.map(_.swap).toMap
    val edges = new Array[Array[Int]](index.size)
    index.foreach {
      case (key, i) =>
        val ts = targets(key).obj("targets").arr
        val deps = ts.iterator.map(dep => index(dep.str)).toArray
        edges(i) = deps
    }
    Graph(export, index.apply _, rindex.apply _, edges)
  }
}
