package scala.meta.internal.tvp
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

/**
 * All the ongoing compilations for all build targets in this workspace
 */
trait TreeViewCompilations {
  def isEmpty: Boolean
  def size: Int
  def buildTargets: Iterator[BuildTargetIdentifier]
  def get(id: BuildTargetIdentifier): Option[TreeViewCompilation]
}
