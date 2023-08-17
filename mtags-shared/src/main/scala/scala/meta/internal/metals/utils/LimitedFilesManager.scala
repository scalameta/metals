package scala.meta.internal.metals.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.meta.internal.metals.TimeFormatter

class LimitedFilesManager(
    directory: Path,
    fileLimit: Int,
    prefixPattern: String
) {
  private val fileNameRegex = s"${prefixPattern}([-+]?[0-9]+)".r

  def getAllFiles(): List[TimestampedFile] = {
    if (Files.exists(directory) && Files.isDirectory(directory)) {
      val oldFormat =
        directory.toFile().listFiles().filter(_.isFile())
      directoriesWithDate.flatMap(filesWithDate) ++
        oldFormat.flatMap(timestampedFile).toList
    } else List()
  }

  def directoriesWithDate: List[File] =
    if (Files.exists(directory) && Files.isDirectory(directory))
      directory
        .toFile()
        .listFiles()
        .toList
        .filter(d => d.isDirectory() && TimeFormatter.hasDateName(d.getName()))
    else List()

  def deleteOld(limit: Int = fileLimit): List[TimestampedFile] = {
    val files = getAllFiles()
    if (files.length > limit) {
      val filesToDelete = files
        .sortBy(_.timestamp)
        .slice(0, files.length - limit)
      filesToDelete.foreach { f => Files.delete(f.toPath) }
      val emptyDateDirectories =
        directoriesWithDate.filter(_.listFiles().isEmpty)
      emptyDateDirectories.foreach { d => Files.delete(d.toPath) }
      filesToDelete
    } else List()
  }

  private def timestampedFile(file: File): Option[TimestampedFile] = {
    file.getName() match {
      case fileNameRegex(time) => Some(TimestampedFile(file, time.toLong))
      case _: String => None
    }
  }

  private def filesWithDate(dir: File): List[TimestampedFile] = {
    val date = dir.getName
    dir.listFiles().flatMap(withTimeAndDate(_, date)).toList
  }

  private def withTimeAndDate(
      file: File,
      date: String
  ): Option[TimestampedFile] = {
    file.getName match {
      case WithTimestamp(time) =>
        TimeFormatter
          .parse(time, date)
          .map(timestamp => TimestampedFile(file, timestamp))
      case _ => None
    }
  }

  object WithTimestamp {
    private val prefix = prefixPattern.r
    def unapply(filename: String): Option[String] = {
      for {
        prefixMatch <- prefix.findPrefixMatchOf(filename)
        time = prefixMatch.after.toString
      } yield time
    }
  }
}

case class TimestampedFile(file: File, timestamp: Long) {
  def toPath: Path = file.toPath()
  def name: String = file.getName()
}
