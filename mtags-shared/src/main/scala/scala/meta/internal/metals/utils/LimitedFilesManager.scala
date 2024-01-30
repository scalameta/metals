package scala.meta.internal.metals.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Try
import scala.util.matching.Regex

import scala.meta.internal.metals.TimeFormatter
import scala.meta.pc.TimestampedFile

class LimitedFilesManager(
    directory: Path,
    fileLimit: Int,
    prefixRegex: Regex,
    extension: String
) {

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
    for {
      reMatch <- prefixRegex.findPrefixMatchOf(file.getName())
      timeStr = reMatch.after.toString.stripSuffix(extension)
      time <- Try(timeStr.toLong).toOption
    } yield new TimestampedFile(file, time)
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
          .map(timestamp => new TimestampedFile(file, timestamp))
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
