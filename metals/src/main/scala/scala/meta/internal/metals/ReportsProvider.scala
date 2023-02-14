package scala.meta.internal.metals

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

class Reports(workspace: AbsolutePath) {
  private lazy val reportsDir =
    workspace.resolve(Directories.reports).withExec { d =>
      Files.createDirectories(d.toNIO)
    }
  val unsanitized =
    new ReportsProvider(workspace, Directories.reports.resolve("metals-full"))
  val incognito =
    new ReportsProvider(workspace, Directories.reports.resolve("metals"))
  val bloop =
    new ReportsProvider(workspace, Directories.reports.resolve("bloop"))

  def all: List[ReportsProvider] = List(unsanitized, incognito, bloop)
  def allToZip: List[ReportsProvider] = List(incognito, bloop)

  def zipReports(): Path = {
    val path = reportsDir.resolve(Reports.ZIP_FILE_NAME).toNIO
    val zipOut = new ZipOutputStream(Files.newOutputStream(path))

    for {
      reportsProvider <- allToZip
      report <- reportsProvider.getReports
    } {
      val zipEntry = new ZipEntry(report.name)
      zipOut.putNextEntry(zipEntry)
      zipOut.write(Files.readAllBytes(report.toPath))
    }
    zipOut.close()

    path
  }

  def cleanUpOldReports(
      maxReportsNumber: Int = Reports.MAX_NUMBER_OF_REPORTS
  ): Unit = {
    all.foreach(_.cleanUpOldReports(maxReportsNumber))
  }

  def deleteAll(): Unit = {
    all.foreach(_.deleteAll())
    Files.delete(reportsDir.resolve(Reports.ZIP_FILE_NAME).toNIO)
  }
}

class ReportsProvider(workspace: AbsolutePath, pathToReports: RelativePath) {
  private lazy val reportsDir =
    workspace.resolve(pathToReports).withExec { d =>
      Files.createDirectories(d.toNIO)
    }

  private lazy val userHome = Option(System.getProperty("user.home"))

  def createReport(name: String, text: String): AbsolutePath =
    reportsDir
      .resolve(s"r_${name}_${System.currentTimeMillis()}")
      .withExec(_.writeText(sanitize(text)))

  private def sanitize(text: String) = {
    val textAfterWokspaceReplace =
      text.replace(workspace.toString(), Reports.WORKSPACE_STR)
    userHome
      .map(textAfterWokspaceReplace.replace(_, Reports.HOME_STR))
      .getOrElse(textAfterWokspaceReplace)
  }

  def cleanUpOldReports(
      maxReportsNumber: Int = Reports.MAX_NUMBER_OF_REPORTS
  ): List[Report] = {
    val reports = getReports
    if (reports.length > maxReportsNumber) {
      val filesToDelete = reports
        .sortBy(_.timestamp)
        .slice(0, reports.length - maxReportsNumber)
      filesToDelete.foreach { f => Files.delete(f.toPath) }
      filesToDelete
    } else List()
  }

  def getReports: List[Report] = {
    val reportsDir = workspace.resolve(pathToReports)
    if (reportsDir.exists && reportsDir.isDirectory) {
      reportsDir.toFile.listFiles().toList.map(Report.fromFile(_)).collect {
        case Some(l) => l
      }
    } else List()
  }

  def deleteAll(): Unit = getReports.foreach(r => Files.delete(r.toPath))
}

object Reports {
  val MAX_NUMBER_OF_REPORTS = 30
  val WORKSPACE_STR = "<WORKSPACE>"
  val HOME_STR = "<HOME>"
  val ZIP_FILE_NAME = "reports.zip"
}

case class Report(file: File, timestamp: Long) {
  def toPath: Path = file.toPath()
  def name: String = file.getName()
}

object Report {
  def fromFile(file: File): Option[Report] = {
    val reportRegex = "r_.*_([-+]?[0-9]+)".r
    file.getName() match {
      case reportRegex(time) => Some(Report(file, time.toLong))
      case _: String => None
    }
  }
}
