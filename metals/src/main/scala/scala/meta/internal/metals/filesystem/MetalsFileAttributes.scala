package scala.meta.internal.metals.filesystem

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.ZonedDateTime

final case class MetalsFileAttributes(path: MetalsPath, isFile: Boolean)
    extends BasicFileAttributes {

  override def lastModifiedTime(): FileTime = MetalsFileAttributes.zeroTime

  override def lastAccessTime(): FileTime = MetalsFileAttributes.zeroTime

  override def creationTime(): FileTime = MetalsFileAttributes.zeroTime

  override def isRegularFile(): Boolean = isFile

  override def isDirectory(): Boolean = !isFile

  override def isSymbolicLink(): Boolean = false

  override def isOther(): Boolean = false

  override def size(): Long = 0L

  override def fileKey(): Object = path
}

object MetalsFileAttributes {
  val zeroTime: FileTime = FileTime.from(ZonedDateTime.now().toInstant())
}
