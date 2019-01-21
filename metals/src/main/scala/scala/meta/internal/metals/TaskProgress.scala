package scala.meta.internal.metals

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
}

object TaskProgress {
  def unapply(p: TaskProgress): Option[Long] = {
    val percentage = p.percentage
    if (percentage >= 0 && percentage <= 100) Some(percentage)
    else None
  }
  def empty: TaskProgress = new TaskProgress(0, 0)
}
