package scala.meta.internal.tvp

import scala.meta.internal.metals.Timer

/**
 * The progress report of the compilation of a single build target to display in a tree view
 */
trait TreeViewCompilation {
  def timer: Timer
  def progressPercentage: Int
}
