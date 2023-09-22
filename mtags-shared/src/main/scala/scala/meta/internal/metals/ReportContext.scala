package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.metals.utils.LimitedFilesManager
import scala.meta.internal.metals.utils.TimestampedFile
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
  ): List[TimestampedFile]
  def getReports(): List[TimestampedFile]
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
  private val limitedFilesManager =
    new LimitedFilesManager(
      reportsDir,
      StdReportContext.MAX_NUMBER_OF_REPORTS,
      "r_.*_"
    )

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
        val path = reportPath(report.name)
        path.getParent.createDirectories()
        sanitizedId.foreach(reported += _)
        val idString = sanitizedId.map(id => s"$idPrefix$id\n").getOrElse("")
        path.writeText(s"$idString${sanitize(report.fullText)}")
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

  private def reportPath(name: String): Path = {
    val date = TimeFormatter.getDate()
    val time = TimeFormatter.getTime()
    val filename = s"r_${name}_${time}"
    reportsDir.resolve(date).resolve(filename)
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[TimestampedFile] = limitedFilesManager.deleteOld(maxReportsNumber)

  override def getReports(): List[TimestampedFile] =
    limitedFilesManager.getAllFiles()

  override def deleteAll(): Unit = {
    getReports().foreach(r => Files.delete(r.toPath))
    limitedFilesManager.directoriesWithDate.foreach { d =>
      Files.delete(d.toPath)
    }
  }

}

object StdReportContext {
  val MAX_NUMBER_OF_REPORTS = 30
  val WORKSPACE_STR = "<WORKSPACE>"
  val HOME_STR = "<HOME>"
  val ZIP_FILE_NAME = "reports.zip"

  def reportsDir: Path = Paths.get(".metals").resolve(".reports")
}

object EmptyReporter extends Reporter {

  override def create(report: => Report, ifVerbose: Boolean): Option[Path] =
    None

  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    List()

  override def getReports(): List[TimestampedFile] = List()

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

  def fullText: String =
    error match {
      case Some(error) =>
        s"""|$error
            |$text
            |
            |error stacktrace:
            |${error.getStackTrace().mkString("\n\t")}
            |""".stripMargin
      case None => text
    }
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
