package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import scala.meta.io.AbsolutePath
import MetalsEnrichments._

class ActiveFiles(time: Time) {
  private case class Event(timer: Timer, path: AbsolutePath) {
    def isStale: Boolean =
      timer.elapsedSeconds > 2
  }
  private val paths = new ConcurrentLinkedQueue[Event]()
  def add(path: AbsolutePath): Unit = {
    paths.removeIf(_.isStale)
    paths.add(Event(new Timer(time), path))
  }

  def isRecentlyActive(path: AbsolutePath): Boolean =
    paths.asScala.exists(_.path == path)
}
