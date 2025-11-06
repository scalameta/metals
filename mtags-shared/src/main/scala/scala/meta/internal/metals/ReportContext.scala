package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

import scala.meta.internal.metals.utils.LimitedFilesManager
import scala.meta.internal.metals.utils.TimestampedFile
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.mtags.MD5

import org.slf4j.LoggerFactory

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
  def name: String
  def create(report: => Report, ifVerbose: Boolean = false): Option[Path]
  def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): List[TimestampedFile]
  def getReports(): List[TimestampedFile]
  def deleteAll(): Unit
  def sanitize(message: String) = message
}

class StdReportContext(
    workspace: Path,
    resolveBuildTarget: Option[URI] => Option[String],
    level: ReportLevel = ReportLevel.Info
) extends ReportContext {
  val reportsDir: Path = workspace.resolve(StdReportContext.reportsDir)

  val unsanitized: StdReporter =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir,
      resolveBuildTarget,
      level,
      "metals-full"
    )
  val incognito: StdReporter =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir,
      resolveBuildTarget,
      level,
      "metals"
    )
  val bloop: StdReporter =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir,
      resolveBuildTarget,
      level,
      "bloop"
    )

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): Unit = {
    all.foreach(_.cleanUpOldReports(maxReportsNumber))
  }

  override def deleteAll(): Unit = {
    all.foreach(_.deleteAll())
    val zipFile = reportsDir.resolve(StdReportContext.ZIP_FILE_NAME)
    if (Files.exists(zipFile)) Files.delete(zipFile)
  }
}

class StdReporter(
    workspace: Path,
    pathToReports: Path,
    resolveBuildTarget: Option[URI] => Option[String],
    level: ReportLevel,
    override val name: String
) extends Reporter {
  private val logger = LoggerFactory.getLogger(classOf[StdReporter].getName)

  val maybeReportsDir: Path =
    workspace.resolve(pathToReports).resolve(name)
  private lazy val reportsDir = maybeReportsDir.createDirectories()
  private val limitedFilesManager =
    new LimitedFilesManager(
      maybeReportsDir,
      StdReportContext.MAX_NUMBER_OF_REPORTS,
      ReportFileName.pattern,
      ".md"
    )

  private lazy val userHome = Option(System.getProperty("user.home"))

  private val initialized = new AtomicBoolean(false)
  private val reported = new AtomicReference(Map[String, Path]())

  def readInIds(): Unit = {
    val reports = getReports().flatMap { report =>
      try {
        val lines = Files.readAllLines(report.file.toPath())
        if (lines.size() > 0) {
          lines.get(0) match {
            case id if id.startsWith(Report.idPrefix) =>
              Some((id.stripPrefix(Report.idPrefix) -> report.toPath))
            case _ => None
          }
        } else None
      } catch {
        case NonFatal(_) => None
      }
    }.toMap
    reported.updateAndGet(_ ++ reports)
  }

  override def create(
      report: => Report,
      ifVerbose: Boolean = false
  ): Option[Path] =
    if (ifVerbose && !level.isVerbose) None
    else
      Try {
        if (initialized.compareAndSet(false, true)) {
          readInIds()
        }
        val sanitizedId = report.id.map(sanitize)
        val path = reportPath(report)

        val optDuplicate =
          for {
            id <- sanitizedId
            reportedMap = reported.getAndUpdate(map =>
              if (map.contains(id)) map else map + (id -> path)
            )
            duplicate <- reportedMap.get(id)
          } yield duplicate

        val pathToReport = optDuplicate.getOrElse {
          path.createDirectories()
          path.writeText(sanitize(report.fullText(withIdAndSummary = true)))
          path
        }
        if (!ifVerbose) {
          logger.error(
            s"${report.shortSummary} (full report at: $pathToReport)"
          )
        }
        pathToReport
      }.toOption

  override def sanitize(text: String): String = {
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
      resolveBuildTarget(report.path)
        .map(_.replaceAll(":", "_"))
        .map("~" ++ _ ++ "~")
        .getOrElse("")
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

  override def name = "empty-reporter"
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
    shortSummary: String,
    path: Option[URI] = None,
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
      id.orElse(
        error.map(error =>
          MD5.compute(s"${name}:${error.getStackTrace().mkString("\n")}")
        )
      ).foreach(id => sb.append(s"${Report.idPrefix}$id\n"))
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
      path: Option[URI]
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

  val idPrefix = "error id: "
  val summaryTitle = "#### Short summary:"
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
  val pattern: Regex = "r_(?<name>[^()~]*)(~(?<buildTarget>.*)~)?_".r

  def getReportNameAndBuildTarget(
      file: TimestampedFile
  ): (String, Option[String]) =
    pattern.findPrefixMatchOf(file.name) match {
      case None => (file.name, None)
      case Some(foundMatch) =>
        (foundMatch.group("name"), Option(foundMatch.group("buildTarget")))
    }

}
