package scala.meta.internal.metals

final case class FileWatcherConfig(value: String) {
  def isAuto: Boolean = value == "auto"
  def isCustom: Boolean = value == "custom"
  def isNone: Boolean = value == "none"
}

object FileWatcherConfig {
  def auto = new FileWatcherConfig("auto")
  def custom = new FileWatcherConfig("custom")
  def none = new FileWatcherConfig("none")
  def default =
    new FileWatcherConfig(System.getProperty("metals.file-watcher", "auto"))
}
