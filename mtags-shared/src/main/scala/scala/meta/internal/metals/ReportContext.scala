package scala.meta.internal.metals

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.mtags.CommonMtagsEnrichments._

trait ReportContext {
  def unsanitized: Reporter
  def incognito: Reporter
  def bloop: Reporter
  def all: List[Reporter] = List(unsanitized, incognito, bloop)
  def allToZip: List[Reporter] = List(incognito, bloop)
  def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): Unit = all.foreach(_.cleanUpOldReports(maxReportsNumber))
  def deleteAll(): Unit = all.foreach(_.deleteAll())
}

trait Reporter {
  def createReport(name: String, text: String): Option[Path]
  def createReport(
      name: String,
      text: String,
      e: Throwable
  ): Option[Path]
  def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[Report]
  def getReports(): List[Report]
  def deleteAll(): Unit
}

class StdReportContext(workspace: Path) extends ReportContext {
  lazy val reportsDir: Path =
    workspace.resolve(StdReportContext.reportsDir).createDirectories()

  val unsanitized =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("metals-full")
    )
  val incognito =
    new StdReporter(workspace, StdReportContext.reportsDir.resolve("metals"))
  val bloop =
    new StdReporter(workspace, StdReportContext.reportsDir.resolve("bloop"))

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): Unit = {
    all.foreach(_.cleanUpOldReports(maxReportsNumber))
  }

  override def deleteAll(): Unit = {
    all.foreach(_.deleteAll())
    Files.delete(reportsDir.resolve(StdReportContext.ZIP_FILE_NAME))
  }
}

class StdReporter(workspace: Path, pathToReports: Path) extends Reporter {
  private lazy val reportsDir =
    workspace.resolve(pathToReports).createDirectories()

  private lazy val userHome = Option(System.getProperty("user.home"))

  override def createReport(
      name: String,
      text: String
  ): Option[Path] = {
    val path = reportsDir.resolve(s"r_${name}_${System.currentTimeMillis()}")
    path.writeText(sanitize(text))
    Some(path)
  }

  override def createReport(
      name: String,
      text: String,
      e: Throwable
  ): Option[Path] =
    createReport(
      name,
      s"""|$text
          |Error message: ${e.getMessage()}
          |Error: $e
          |""".stripMargin
    )

  private def sanitize(text: String) = {
    val textAfterWokspaceReplace =
      text.replace(workspace.toString(), StdReportContext.WORKSPACE_STR)
    userHome
      .map(textAfterWokspaceReplace.replace(_, StdReportContext.HOME_STR))
      .getOrElse(textAfterWokspaceReplace)
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[Report] = {
    val reports = getReports()
    if (reports.length > maxReportsNumber) {
      val filesToDelete = reports
        .sortBy(_.timestamp)
        .slice(0, reports.length - maxReportsNumber)
      filesToDelete.foreach { f => Files.delete(f.toPath) }
      filesToDelete
    } else List()
  }

  override def getReports(): List[Report] = {
    val reportsDir = workspace.resolve(pathToReports)
    if (reportsDir.exists && Files.isDirectory(reportsDir)) {
      reportsDir.toFile.listFiles().toList.map(Report.fromFile(_)).collect {
        case Some(l) => l
      }
    } else List()
  }

  override def deleteAll(): Unit =
    getReports().foreach(r => Files.delete(r.toPath))

}

object StdReportContext {
  val MAX_NUMBER_OF_REPORTS = 30
  val WORKSPACE_STR = "<WORKSPACE>"
  val HOME_STR = "<HOME>"
  val ZIP_FILE_NAME = "reports.zip"

  def reportsDir: Path = Paths.get(".metals").resolve(".reports")
  def apply(path: Path) = new StdReportContext(path)
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

object EmptyReporter extends Reporter {

  override def createReport(name: String, text: String): Option[Path] =
    None

  override def createReport(
      name: String,
      text: String,
      e: Throwable
  ): Option[Path] = None

  override def cleanUpOldReports(maxReportsNumber: Int): List[Report] = List()

  override def getReports(): List[Report] = List()

  override def deleteAll(): Unit = {}
}

object EmptyReportContext extends ReportContext {

  override def unsanitized: Reporter = EmptyReporter

  override def incognito: Reporter = EmptyReporter

  override def bloop: Reporter = EmptyReporter
}
