package scala.meta.internal.metals.watcher

import java.nio.file.Path

final case class FileWatcherEvent(
    eventType: FileWatcherEvent.EventType,
    path: Path
)

object FileWatcherEvent {
  sealed trait EventType

  object EventType {
    case object Create extends EventType
    case object Modify extends EventType
    case object Delete extends EventType
    case object Overflow extends EventType
  }

  def create(path: Path): FileWatcherEvent =
    FileWatcherEvent(EventType.Create, path)
  def modify(path: Path): FileWatcherEvent =
    FileWatcherEvent(EventType.Modify, path)
  def delete(path: Path): FileWatcherEvent =
    FileWatcherEvent(EventType.Delete, path)
  // indicates that file watching events may have been lost
  // for the given path, or for an unknown path if path is null
  def overflow(path: Path): FileWatcherEvent =
    FileWatcherEvent(EventType.Overflow, path)
}
