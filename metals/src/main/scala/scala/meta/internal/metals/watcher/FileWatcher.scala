package scala.meta.internal.metals.watcher

trait FileWatcher {
  def start(): Unit
  def cancel(): Unit
}

object NoopFileWatcher extends FileWatcher {
  def cancel(): Unit = {}
  def start(): Unit = {}
}
