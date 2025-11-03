package scala.meta.internal.metals.mbt

import scala.meta.io.AbsolutePath

case class GitFileStatus(
    value: String,
    file: AbsolutePath,
) {
  def isAdded: Boolean = value == "A"
  def isModified: Boolean = value == "M"
  def isDeleted: Boolean = value == "D"
  def isUntracked: Boolean = value == "??"
}
