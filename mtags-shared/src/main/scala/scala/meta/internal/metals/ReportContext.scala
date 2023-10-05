package scala.meta.internal.metals

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.matching.Regex

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
  def getReports(): List[TimestampedFile]
}

trait Reporter {
  def create(report: => Report, ifVerbose: Boolean = false): Option[Path]
  def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[TimestampedFile]
  def getReports(): List[TimestampedFile]
  def deleteAll(): Unit
}

class StdReportContext(
    workspace: Path,
    resolveBuildTarget: Option[String] => Option[String],
    level: ReportLevel = ReportLevel.Info
) extends ReportContext {
  val reportsDir: Path = workspace.resolve(StdReportContext.reportsDir)

  val unsanitized =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("metals-full"),
      resolveBuildTarget,
      level
    )
  val incognito =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("metals"),
      resolveBuildTarget,
      level
    )
  val bloop =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir.resolve("bloop"),
      resolveBuildTarget,
      level
    )

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): Unit = {
    all.foreach(_.cleanUpOldReports(maxReportsNumber))
  }

  override def getReports(): List[TimestampedFile] = all.flatMap(_.getReports())

  override def deleteAll(): Unit = {
    all.foreach(_.deleteAll())
    val zipFile = reportsDir.resolve(StdReportContext.ZIP_FILE_NAME)
    if (Files.exists(zipFile)) Files.delete(zipFile)
  }
}

class StdReporter(
    workspace: Path,
    pathToReports: Path,
    resolveBuildTarget: Option[String] => Option[String],
    level: ReportLevel
) extends Reporter {
  private lazy val maybeReportsDir: Path = workspace.resolve(pathToReports)
  private lazy val reportsDir = maybeReportsDir.createDirectories()
  private val limitedFilesManager =
    new LimitedFilesManager(
      maybeReportsDir,
      StdReportContext.MAX_NUMBER_OF_REPORTS,
      ReportFileName.pattern,
      ".md"
    )

  private lazy val userHome = Option(System.getProperty("user.home"))

  private var initialized = false
  private var reported = Set.empty[String]

  def readInIds(): Unit = {
    reported = getReports().flatMap { report =>
      val lines = Files.readAllLines(report.file.toPath())
      if (lines.size() > 0) {
        lines.get(0) match {
          case id if id.startsWith(Report.idPrefix) =>
            Some(id.stripPrefix(Report.idPrefix))
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
        val path = reportPath(report)
        path.getParent.createDirectories()
        sanitizedId.foreach(reported += _)
        path.writeText(sanitize(report.fullText(withIdAndSummary = true)))
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

  private def reportPath(report: Report): Path = {
    val date = TimeFormatter.getDate()
    val time = TimeFormatter.getTime()
    val buildTargetPart =
      resolveBuildTarget(report.path).map(":" ++ _).getOrElse("")
    val filename = s"r_${report.name}${buildTargetPart}_${time}.md"
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

  override def getReports(): List[TimestampedFile] = List.empty

  override def unsanitized: Reporter = EmptyReporter

  override def incognito: Reporter = EmptyReporter

  override def bloop: Reporter = EmptyReporter
}

case class Report(
    name: String,
    text: String,
    shortSummary: String,
    path: Option[String] = None,
    id: Option[String] = None,
    error: Option[Throwable] = None
) {
  def extend(moreInfo: String): Report =
    this.copy(
      text = s"""|${this.text}
                 |$moreInfo"""".stripMargin
    )

  def fullText(withIdAndSummary: Boolean): String = {
    val sb = new StringBuilder
    if (withIdAndSummary) {
      id.foreach(id => sb.append(s"${Report.idPrefix}$id\n"))
    }
    path.foreach(path => sb.append(s"$path\n"))
    error match {
      case Some(error) =>
        sb.append(
          s"""|### $error
              |
              |$text
              |
              |#### Error stacktrace:
              |
              |```
              |${error.getStackTrace().mkString("\n\t")}
              |```
              |""".stripMargin
        )
      case None => sb.append(s"$text\n")
    }
    if (withIdAndSummary)
      sb.append(s"""|${Report.summaryTitle}
                    |
                    |$shortSummary""".stripMargin)
    sb.result()
  }
}

object Report {

  def apply(
      name: String,
      text: String,
      error: Throwable,
      path: Option[String]
  ): Report =
    Report(
      name,
      text,
      shortSummary = error.toString(),
      path = path,
      error = Some(error)
    )

  def apply(name: String, text: String, error: Throwable): Report =
    Report(name, text, error, path = None)

  val idPrefix = "id: "
  val summaryTitle = "#### Short summary: "
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

object ReportFileName {
  val pattern: Regex = "r_([^:]*)(:.*)?_".r

  def getReportNameAndBuildTarget(file: File): (String, Option[String]) =
    pattern.findPrefixMatchOf(file.getName()) match {
      case None => (file.getName(), None)
      case Some(foundMatch) =>
        (
          foundMatch.group(1),
          Option(foundMatch.group(2)).map(_.stripPrefix(":"))
        )
    }
}
