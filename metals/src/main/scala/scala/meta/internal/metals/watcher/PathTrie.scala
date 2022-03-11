package scala.meta.internal.metals.watcher

import java.nio.file.Path

import scala.collection.JavaConverters._

import scala.meta.internal.metals.watcher.PathTrie._

/**
 * Trie representation of a set of paths
 *
 * Each path segment is represented by a node in a tree.
 * Can be used to efficiently check if the trie contains
 * a prefix of a given path
 */
class PathTrie private (root: Node) {

  def containsPrefixOf(path: Path): Boolean = {
    val segments: List[String] = toSegments(path)

    def go(segments: List[String], node: Node): Boolean = {
      (segments, node) match {
        case (_, Leaf) => true
        case (Nil, _) => false
        case (head :: tail, Single(segment, child, _)) =>
          if (head == segment) go(tail, child) else false
        case (head :: tail, Multi(children, _)) =>
          children.get(head).fold(false)(go(tail, _))
      }
    }
    go(segments, root)
  }

  def longestPrefixes(fsRoot: Path, maxRoots: Int): Iterable[Path] = {
    def go(acc: Path, node: Node, availableRoots: Int): Iterable[Path] =
      node match {
        case Leaf => acc :: Nil
        case Single(segment, child, terminal) =>
          if (terminal) acc :: Nil
          else go(acc.resolve(segment), child, availableRoots)
        case Multi(children, terminal) =>
          if (terminal || children.size > availableRoots) acc :: Nil
          else
            children.flatMap { case (segment, child) =>
              go(acc.resolve(segment), child, availableRoots / children.size)
            }
      }
    go(fsRoot, root, maxRoots)
  }
}

object PathTrie {
  private sealed trait Node

  private case object Leaf extends Node
  private case class Single(segment: String, child: Node, terminal: Boolean)
      extends Node
  private case class Multi(children: Map[String, Node], terminal: Boolean)
      extends Node

  def apply(paths: Set[Path]): PathTrie = {
    def construct(paths: Set[List[String]]): Node = {
      val terminal = paths.contains(Nil)
      val groupedNonEmptyPaths =
        paths
          .filter(_.nonEmpty)
          .groupBy(_.head)
          .mapValues(_.map(_.tail))
          .toList

      groupedNonEmptyPaths match {
        case Nil => Leaf
        case singleGroup :: Nil =>
          Single(singleGroup._1, construct(singleGroup._2), terminal)
        case _ =>
          val children = groupedNonEmptyPaths.map {
            case (topSegment, tailSegments) =>
              topSegment -> construct(tailSegments)
          }.toMap
          Multi(children, terminal)
      }
    }

    new PathTrie(
      construct(
        paths.map(toSegments)
      )
    )
  }

  private def toSegments(path: Path): List[String] =
    path.iterator().asScala.map(_.toString()).toList
}
