package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.util.matching.Regex

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.utils.LimitedFilesManager
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.StandardReport
import scala.meta.pc.Report
import scala.meta.pc.ReportContext
import scala.meta.pc.Reporter
import scala.meta.pc.TimestampedFile

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
  ): Unit =
    super.cleanUpOldReports(maxReportsNumber)

  override def deleteAll(): Unit = {
    all.forEach(_.deleteAll())
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
    val reports = getReports().asScala.flatMap { report =>
      val lines = Files.readAllLines(report.file.toPath())
      if (lines.size() > 0) {
        lines.get(0) match {
          case id if id.startsWith(StandardReport.idPrefix) =>
            Some((id.stripPrefix(StandardReport.idPrefix) -> report.toPath))
          case _ => None
        }
      } else None
    }.toMap
    reported.updateAndGet(_ ++ reports)
  }

  override def create(
      report: Report,
      ifVerbose: Boolean = false
  ): ju.Optional[Path] =
    if (ifVerbose && !level.isVerbose) ju.Optional.empty()
    else {
      if (initialized.compareAndSet(false, true)) {
        readInIds()
      }
      val sanitizedId: Option[String] = report.id.asScala.map(sanitize(_))
      val path = reportPath(report)

      val optDuplicate = sanitizedId.flatMap { id =>
        val reportedMap = reported.getAndUpdate(map =>
          if (map.contains(id)) map else map + (id -> path)
        )
        reportedMap.get(id)
      }

      ju.Optional.of(
        optDuplicate.getOrElse {
          path.createDirectories()
          path.writeText(sanitize(report.fullText(true)))
          path
        }
      )
    }

  override def sanitize(text: String): String = sanitizer(text)

  private def reportPath(report: Report): Path = {
    val date = TimeFormatter.getDate()
    val time = TimeFormatter.getTime()
    val buildTargetPart =
      resolveBuildTarget(report.path.asScala)
        .map("_(" ++ _ ++ ")")
        .getOrElse("")
    val filename = s"r_${report.name}${buildTargetPart}_${time}.md"
    reportsDir.resolve(date).resolve(filename)
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): ju.List[TimestampedFile] =
    limitedFilesManager.deleteOld(maxReportsNumber).asJava

  override def getReports(): ju.List[TimestampedFile] =
    limitedFilesManager.getAllFiles().asJava

  override def deleteAll(): Unit = {
    getReports().forEach(r => Files.delete(r.toPath))
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
class MirroredReportContext(primary: ReportContext, auxilary: ReportContext*)
    extends ReportContext {
  private def mirror(selector: ReportContext => Reporter): Reporter =
    new MirroredReporter(selector(primary), auxilary.map(selector): _*)
  override lazy val unsanitized: Reporter = mirror(_.unsanitized)
  override lazy val incognito: Reporter = mirror(_.incognito)
  override lazy val bloop: Reporter = mirror(_.bloop)
}

private class MirroredReporter(
    primaryReporter: Reporter,
    auxilaryReporters: Reporter*
) extends Reporter {
  override val name: String =
    s"${primaryReporter.name}-mirror-${auxilaryReporters.map(_.name).mkString("|")}"
  private final def allReporters = primaryReporter :: auxilaryReporters.toList

  override def create(report: Report, ifVerbose: Boolean): ju.Optional[Path] = {
    auxilaryReporters.foreach(_.create(report, ifVerbose))
    primaryReporter.create(report, ifVerbose)
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): ju.List[TimestampedFile] =
    allReporters.flatMap(_.cleanUpOldReports(maxReportsNumber).asScala).asJava

  override def getReports(): ju.List[TimestampedFile] =
    allReporters.flatMap(_.getReports().asScala).asJava

  override def deleteAll(): Unit =
    allReporters.foreach(_.deleteAll())
}

object EmptyReporter extends Reporter {

  Boolean
  override def name = "empty-reporter"
  override def create(report: Report, ifVerbose: Boolean): ju.Optional[Path] =
    ju.Optional.empty()

  override def cleanUpOldReports(
      maxReportsNumber: Int = StdReportContext.MAX_NUMBER_OF_REPORTS
  ): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def getReports(): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def deleteAll(): Unit = {}
}

object EmptyReportContext extends ReportContext {

  override def unsanitized: Reporter = EmptyReporter

  override def incognito: Reporter = EmptyReporter

  override def bloop: Reporter = EmptyReporter
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
