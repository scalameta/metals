package scala.meta.internal.metals

import scala.meta.internal.metals.FileWatcherConfig._

sealed abstract class FileWatcherConfig {
  def isCustom: Boolean = this == custom
  def isAuto: Boolean = this == auto
  def isNone: Boolean = this == none
}
object FileWatcherConfig {
  case object auto extends FileWatcherConfig
  case object custom extends FileWatcherConfig
  case object none extends FileWatcherConfig
  def default: FileWatcherConfig =
    System.getProperty("metals.file-watcher", "none") match {
      case "auto" => auto
      case "custom" => custom
      case _ => none
    }
}
