package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue

import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

/**
 * Keeps track of files that have been "recently active"
 *
 * Used to ignore file watching events that follow right after
 * textDocument/didSave events.
 */
final class ActiveFiles(time: Time) {
  private case class Event(timer: Timer, path: AbsolutePath) {
    def isActive: Boolean =
      !isStale
    def isStale: Boolean =
      timer.elapsedSeconds > 2
  }
  private val paths = new ConcurrentLinkedQueue[Event]()
  def add(path: AbsolutePath): Unit = {
    paths.removeIf(_.isStale)
    paths.add(Event(new Timer(time), path))
  }

  def isRecentlyActive(path: AbsolutePath): Boolean = {
    paths.asScala.exists(p => p.isActive && p.path == path)
  }

  def pollRecent(): Option[AbsolutePath] = {
    paths.removeIf(_.isStale)
    Option(paths.poll()).map(_.path)
  }
}
