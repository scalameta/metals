package scala.meta.internal.metals.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Try
import scala.util.matching.Regex

import scala.meta.internal.metals.TimeFormatter

class LimitedFilesManager(
    directory: Path,
    fileLimit: Int,
    prefixRegex: Regex,
    extension: String
) {

  def listFiles(directory: Path): List[File] = {
    if (Files.exists(directory) && Files.isDirectory(directory)) {
      val list = directory.toFile().listFiles()
      if (list != null) list.toList else List()
    } else List()
  }

  def getAllFiles(): List[TimestampedFile] = {
    val oldFormat = listFiles(directory).filter(_.isFile())
    directoriesWithDate.flatMap(filesWithDate) ++
      oldFormat.flatMap(timestampedFile).toList
  }

  def directoriesWithDate: List[File] =
    listFiles(directory).filter(d =>
      d.isDirectory() && TimeFormatter.hasDateName(d.getName())
    )

  def deleteOld(limit: Int = fileLimit): List[TimestampedFile] = {
    val files = getAllFiles()
    if (files.length > limit) {
      val filesToDelete = files
        .sortBy(_.timestamp)
        .slice(0, files.length - limit)
      filesToDelete.foreach { f => Files.delete(f.toPath) }
      val emptyDateDirectories =
        directoriesWithDate.filter(d => listFiles(d.toPath()).isEmpty)
      emptyDateDirectories.foreach { d => Files.delete(d.toPath) }
      filesToDelete
    } else List()
  }

  private def timestampedFile(file: File): Option[TimestampedFile] = {
    for {
      reMatch <- prefixRegex.findPrefixMatchOf(file.getName())
      timeStr = reMatch.after.toString.stripSuffix(extension)
      time <- Try(timeStr.toLong).toOption
    } yield TimestampedFile(file, time)
  }

  private def filesWithDate(dir: File): List[TimestampedFile] = {
    val date = dir.getName
    listFiles(dir.toPath()).flatMap(withTimeAndDate(_, date)).toList
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
    def unapply(filename: String): Option[String] = {
      for {
        prefixMatch <- prefixRegex.findPrefixMatchOf(filename)
        time = prefixMatch.after.toString.stripSuffix(extension)
      } yield time
    }
  }
}

case class TimestampedFile(file: File, timestamp: Long) {
  def toPath: Path = file.toPath()
  def name: String = file.getName()
}
