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
  def create(report: => Report, ifVerbose: Boolean = false): Option[Path]
  def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[ReportFile]
  def getReports(): List[ReportFile]
  def deleteAll(): Unit
}

class StdReportContext(workspace: Path, level: ReportLevel = ReportLevel.Info)
    extends ReportContext {
  lazy val reportsDir: Path =
    workspace.resolve(StdReportContext.reportsDir).createDirectories()

  val unsanitized =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("metals-full"),
      level
    )
  val incognito =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("metals"),
      level
    )
  val bloop =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("bloop"),
      level
    )

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

class StdReporter(workspace: Path, pathToReports: Path, level: ReportLevel)
    extends Reporter {
  private lazy val reportsDir =
    workspace.resolve(pathToReports).createDirectories()

  private lazy val userHome = Option(System.getProperty("user.home"))

  private var initialized = false
  private var reported = Set.empty[String]
  private val idPrefix = "id: "

  def readInIds(): Unit = {
    reported = getReports().flatMap { report =>
      val lines = Files.readAllLines(report.file.toPath())
      if (lines.size() > 0) {
        lines.get(0) match {
          case id if id.startsWith(idPrefix) => Some(id.stripPrefix(idPrefix))
          case _ => None
        }
      } else None
    }.toSet
  }

  override def create(
      report: => Report,
      ifVerbose: Boolean = false
  ): Option[Path] =
    if (ifVerbose && !level.isVerbose) None
    else {
      if (!initialized) {
        readInIds()
        initialized = true
      }
      val sanitizedId = report.id.map(sanitize)
      if (sanitizedId.isDefined && reported.contains(sanitizedId.get)) None
      else {
        val path =
          reportsDir.resolve(s"r_${report.name}_${System.currentTimeMillis()}")
        val text = report.error match {
          case Some(error) =>
            s"""|${report.text}
                |Error message: ${error.getMessage()}
                |Error: $error
                |""".stripMargin
          case None => report.text
        }
        sanitizedId.foreach(reported += _)
        val idString = sanitizedId.map(id => s"$idPrefix$id\n").getOrElse("")
        path.writeText(s"$idString${sanitize(text)}")
        Some(path)
      }
    }

  private def sanitize(text: String) = {
    val textAfterWokspaceReplace =
      text.replace(workspace.toString(), StdReportContext.WORKSPACE_STR)
    userHome
      .map(textAfterWokspaceReplace.replace(_, StdReportContext.HOME_STR))
      .getOrElse(textAfterWokspaceReplace)
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[ReportFile] = {
    val reports = getReports()
    if (reports.length > maxReportsNumber) {
      val filesToDelete = reports
        .sortBy(_.timestamp)
        .slice(0, reports.length - maxReportsNumber)
      filesToDelete.foreach { f => Files.delete(f.toPath) }
      filesToDelete
    } else List()
  }

  override def getReports(): List[ReportFile] = {
    val reportsDir = workspace.resolve(pathToReports)
    if (reportsDir.exists && Files.isDirectory(reportsDir)) {
      reportsDir.toFile.listFiles().toList.map(ReportFile.fromFile(_)).collect {
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
}

case class ReportFile(file: File, timestamp: Long) {
  def toPath: Path = file.toPath()
  def name: String = file.getName()
}

object ReportFile {
  def fromFile(file: File): Option[ReportFile] = {
    val reportRegex = "r_.*_([-+]?[0-9]+)".r
    file.getName() match {
      case reportRegex(time) => Some(ReportFile(file, time.toLong))
      case _: String => None
    }
  }
}

object EmptyReporter extends Reporter {

  override def create(report: => Report, ifVerbose: Boolean): Option[Path] =
    None

  override def cleanUpOldReports(maxReportsNumber: Int): List[ReportFile] =
    List()

  override def getReports(): List[ReportFile] = List()

  override def deleteAll(): Unit = {}
}

object EmptyReportContext extends ReportContext {

  override def unsanitized: Reporter = EmptyReporter

  override def incognito: Reporter = EmptyReporter

  override def bloop: Reporter = EmptyReporter
}

case class Report(
    name: String,
    text: String,
    id: Option[String] = None,
    error: Option[Throwable] = None
) {
  def extend(moreInfo: String): Report =
    this.copy(
      text = s"""|${this.text}
                 |$moreInfo"""".stripMargin
    )
}

object Report {
  def apply(name: String, text: String, id: String): Report =
    Report(name, text, id = Some(id))
  def apply(name: String, text: String, error: Throwable): Report =
    Report(name, text, error = Some(error))
}

sealed trait ReportLevel {
  def isVerbose: Boolean
}

object ReportLevel {
  case object Info extends ReportLevel {
    def isVerbose = false
  }

  case object Debug extends ReportLevel {
    def isVerbose = true
  }

  def fromString(level: String): ReportLevel =
    level match {
      case "debug" => Debug
      case _ => Info
    }
}
