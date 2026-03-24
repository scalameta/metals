package scala.meta.internal.pc

import java.net.URI

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

import com.sun.source.tree.BlockTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees

/**
 * Shortens Java source by replacing method, constructor, and initializer bodies with empty blocks,
 * leaving only signatures. Uses the javac Compiler Tree API for accurate parsing.
 */
object JavaSourceShortener {

  private val dummyUri = URI.create("file:///dummy.java")

  /**
   * Returns the source with all method/constructor/initializer bodies replaced by empty blocks.
   * Returns None if the source does not parse as valid Java.
   */
  def shortenBodies(source: String): Option[String] = {
    Try {
      val task = JavaMetalsGlobal.baseCompilationTask(source, dummyUri)
      val units = task.parse().asScala.toList
      val unitOpt = units.headOption
      unitOpt.flatMap { unit =>
        val trees = Trees.instance(task)
        val positions = trees.getSourcePositions()
        val collector = new BodyRangeCollector(unit, positions)
        collector.scan(unit, null)
        val ranges =
          dropNestedRanges(collector.ranges.result())
            .sortBy { case (start, end) =>
              (-start, -end)
            } // highest offsets first
        if (ranges.isEmpty) Some(source)
        else {
          val result = ranges.foldLeft(source) { case (acc, (start, end)) =>
            acc.take(start) + acc.drop(end)
          }
          Some(result)
        }
      }
    }.toOption.flatten
  }

  /**
   * Drops ranges nested inside another range. Nested ranges from the tree walk would otherwise
   * be applied in offset order and corrupt the source after the first removal.
   */
  private def dropNestedRanges(ranges: List[(Int, Int)]): List[(Int, Int)] = {
    ranges
      .sortBy(_._1)
      .foldLeft((List.empty[(Int, Int)], -1)) {
        case ((kept, lastKeptEnd), (start, end)) =>
          if (start >= lastKeptEnd) ((start, end) :: kept, end)
          else (kept, lastKeptEnd)
      }
      ._1
      .reverse
  }

  private class BodyRangeCollector(
      root: CompilationUnitTree,
      positions: SourcePositions
  ) extends TreeScanner[Void, Void] {

    val ranges: mutable.Builder[(Int, Int), List[(Int, Int)]] =
      List.newBuilder[(Int, Int)]

    override def visitMethod(node: MethodTree, _p: Void): Void = {
      val body = node.getBody
      if (body != null) addBodyRange(body)
      super.visitMethod(node, _p)
    }

    override def visitClass(node: ClassTree, _p: Void): Void = {
      node.getMembers.asScala.foreach {
        case block: BlockTree => addBodyRange(block)
        case _ =>
      }
      super.visitClass(node, _p)
    }

    private def addBodyRange(body: BlockTree): Unit = {
      val start = positions.getStartPosition(root, body)
      val end = positions.getEndPosition(root, body)
      if (start >= 0 && end > start + 1) {
        // replace content between braces (exclusive): (start+1, end-1)
        ranges += ((start.toInt + 1, end.toInt - 1))
      }
    }
  }
}
