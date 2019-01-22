package scala.meta.internal.metals

/**
 * Denotes how much of a task has been completed.
 *
 * @param progress must be greater or equal to 0 and smaller than total.
 * @param total the task is complete when progress == total.
 */
class TaskProgress(
    var progress: Long,
    var total: Long
) {
  def update(newProgress: Long, newTotal: Long): Unit = {
    progress = newProgress
    total = newTotal
  }
  def percentage: Int = {
    if (total == 0) 0
    else ((progress.toDouble / total) * 100).toInt
  }
  override def toString: String = s"TaskProgress($progress, $total)"
}

object TaskProgress {
  def unapply(p: TaskProgress): Option[Long] = {
    val percentage = p.percentage
    if (percentage >= 0 && percentage <= 100) Some(percentage)
    else None
  }
  def empty: TaskProgress = new TaskProgress(0, 0)
}
