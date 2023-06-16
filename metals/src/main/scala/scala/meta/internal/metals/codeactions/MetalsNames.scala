package scala.meta.internal.metals.codeactions

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import scala.meta._
import scala.meta.internal.mtags.MtagsEnrichments._

case class MetalsNames(tree: Tree, prefix: String) {

  private lazy val allNames = {
    val top = lastEnclosingStatsTree(tree)
    findAllNames(top)
  }

  private var currentIndex: Int = 0
  // I don't think it's needed to go all the way up to the top parent, but if users keep using same names this might happen, however this is a really edge scenario
  private def lastEnclosingStatsTree(
      t: Tree
  ): Tree = {
    @tailrec
    def loop(tree: Tree): Tree = {
      tree.parent match {
        case Some(t: Template) => t
        case Some(b: Term.Block) => b
        case Some(fy: Term.ForYield)
            if !fy.enums.headOption.exists(_.pos.encloses(t.pos)) =>
          fy
        case Some(f: Term.For) => f
        case Some(df: Defn.Def) => df
        case Some(tf: Term.Function) => tf
        case Some(other) => loop(other)
        case None => t
      }
    }
    loop(t)
  }

  private def findAllNames(t: Tree): Set[String] = {
    val buffer = ListBuffer.empty[String]
    t.traverse { case n: Name =>
      buffer += n.value
    }
    buffer.toSet
  }

  def createNewName(): String = {
    val name = if (currentIndex == 0) prefix else s"$prefix$currentIndex"
    currentIndex += 1
    if (allNames(name)) createNewName()
    else name
  }
}
