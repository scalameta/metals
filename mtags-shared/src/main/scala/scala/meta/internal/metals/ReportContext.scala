package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try
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
    resolveBuildTarget: Option[String] => Option[String],
    level: ReportLevel = ReportLevel.Info
) extends ReportContext {
  val reportsDir: Path = workspace.resolve(StdReportContext.reportsDir)

  val unsanitized =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir,
      resolveBuildTarget,
      level,
      "metals-full"
    )
  val incognito =
    new StdReporter(
      workspace,
      StdReportContext.reportsDir,
      resolveBuildTarget,
      level,
      "metals"
    )
  val bloop =
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
    resolveBuildTarget: Option[String] => Option[String],
    level: ReportLevel,
    override val name: String
) extends Reporter {
  private val sanitizer: ReportSanitizer = new WorkspaceSanitizer(
    Some(workspace)
  )
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

  private val initialized = new AtomicBoolean(false)
  private val reported = new AtomicReference(Map[String, Path]())

  def readInIds(): Unit = {
    val reports = getReports().flatMap { report =>
      val lines = Files.readAllLines(report.file.toPath())
      if (lines.size() > 0) {
        lines.get(0) match {
          case id if id.startsWith(Report.idPrefix) =>
            Some((id.stripPrefix(Report.idPrefix) -> report.toPath))
          case _ => None
        }
      } else None
    }.toMap
    reported.updateAndGet(_ ++ reports)
  }

  override def create(
      report: => Report,
      ifVerbose: Boolean = false
  ): Option[Path] =
    if (ifVerbose && !level.isVerbose) None
    else {
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

      optDuplicate.orElse {
        Try {
          path.createDirectories()
          path.writeText(sanitize(report.fullText(withIdAndSummary = true)))
          path
        }.toOption
      }
    }

  override def sanitize(text: String): String = sanitizer(text)

  private def reportPath(report: Report): Path = {
    val date = TimeFormatter.getDate()
    val time = TimeFormatter.getTime()
    val buildTargetPart =
      resolveBuildTarget(report.path).map("_(" ++ _ ++ ")").getOrElse("")
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

/**
 * Fan-out report context delegating reporting to all underlyin reporters of given type.
 */
class MirroredReportContext(primiary: ReportContext, auxilary: ReportContext*)
    extends ReportContext {
  private def mirror(selector: ReportContext => Reporter): Reporter =
    new MirroredReporter(selector(primiary), auxilary.map(selector): _*)
  override lazy val unsanitized: Reporter = mirror(_.unsanitized)
  override lazy val incognito: Reporter = mirror(_.incognito)
  override lazy val bloop: Reporter = mirror(_.bloop)
}

private class MirroredReporter(
    primaryReporter: Reporter,
    auxilaryReporters: Reporter*
) extends Reporter {
  override val name: String =
    s"${primaryReporter.name}-mirror-${auxilaryReporters.mkString("|")}"
  private final def allReporters = primaryReporter :: auxilaryReporters.toList

  override def create(report: => Report, ifVerbose: Boolean): Option[Path] = {
    auxilaryReporters.foreach(_.create(report, ifVerbose))
    primaryReporter.create(report, ifVerbose)
  }

  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    allReporters.flatMap(_.cleanUpOldReports(maxReportsNumber))

  override def getReports(): List[TimestampedFile] =
    allReporters.flatMap(_.getReports())

  override def deleteAll(): Unit =
    allReporters.foreach(_.deleteAll())
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

  val idPrefix = "error id: "
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
  val pattern: Regex = "r_(?<name>[^()]*)(_\\((?<buildTarget>.*)\\))?_".r

  def getReportNameAndBuildTarget(
      file: TimestampedFile
  ): (String, Option[String]) =
    pattern.findPrefixMatchOf(file.name) match {
      case None => (file.name, None)
      case Some(foundMatch) =>
        (foundMatch.group("name"), Option(foundMatch.group("buildTarget")))
    }

}
