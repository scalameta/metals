package scala.meta.internal.metals.doctor

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.util.Try

import scala.meta.internal.bsp.BspResolvedResult
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.bsp.RegenerateBspConfig
import scala.meta.internal.bsp.ResolvedBloop
import scala.meta.internal.bsp.ResolvedBspOne
import scala.meta.internal.bsp.ResolvedMultiple
import scala.meta.internal.bsp.ResolvedNone
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.FileDecoderProvider
import scala.meta.internal.metals.HtmlBuilder
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.JavaInfo
import scala.meta.internal.metals.JavaTarget
import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.PopupChoiceReset
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ReportFileName
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.doctor.MetalsServiceInfo.FallbackService
import scala.meta.internal.metals.doctor.MetalsServiceInfo.ProjectService
import scala.meta.internal.metals.utils.TimestampedFile
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}

/**
 * Helps the user figure out what is mis-configured in the build through the "Run doctor" command.
 *
 * At the moment, the doctor only validates that SemanticDB is enabled for all projects.
 */
final class Doctor(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    diagnostics: Diagnostics,
    languageClient: MetalsLanguageClient,
    tables: Tables,
    clientConfig: ClientConfiguration,
    mtagsResolver: MtagsResolver,
    folderName: String,
    serviceInfo: => MetalsServiceInfo,
)(implicit ec: ExecutionContext, rc: ReportContext)
    extends TargetsInfoProvider {
  private val hasProblems = new AtomicBoolean(false)
  def currentBuildServer(): Option[BspSession] =
    serviceInfo match {
      case FallbackService => None
      case projectService: ProjectService =>
        projectService.currentBuildServer()
    }

  private val problemResolver =
    new ProblemResolver(
      workspace,
      mtagsResolver,
      currentBuildServer,
      () => clientConfig.isTestExplorerProvider(),
      () => serviceInfo.getJavaInfo(),
    )

  def isUnsupportedBloopVersion(): Boolean =
    problemResolver.isUnsupportedBloopVersion()

  /**
   * Executes the "Reload doctor" server command.
   */
  private def executeReloadDoctor(
      summary: Option[String],
      headDoctor: HeadDoctor,
  ): Unit = {
    val hasProblemsNow = summary.isDefined
    if (hasProblems.get() && !hasProblemsNow) {
      hasProblems.set(false)
      languageClient.showMessage(CheckDoctor.problemsFixed)
    }
    headDoctor.executeRefreshDoctor()
  }

  /**
   * Checks if there are any potential problems and if any, notifies the user.
   */
  def check(headDoctor: HeadDoctor): Unit = {
    scribe.debug(s"running doctor check")
    val scalaTargets = buildTargets.allScala.toList
    val javaTargets = buildTargets.allJava.toList
    scribe.debug(
      s"java targets: ${javaTargets.map(_.info.getDisplayName()).mkString(", ")}"
    )
    val summary = problemResolver.problemMessage(scalaTargets, javaTargets)
    executeReloadDoctor(summary, headDoctor)
    summary match {
      case Some(problem) =>
        val notification = tables.dismissedNotifications.DoctorWarning
        if (!notification.isDismissed) {
          notification.dismiss(2, TimeUnit.MINUTES)
          import scala.meta.internal.metals.Messages.CheckDoctor
          val params = CheckDoctor.params(problem)
          hasProblems.set(true)
          languageClient
            .showMessageRequest(
              params,
              defaultTo = () => {
                languageClient.showMessage(
                  new l.MessageParams(
                    l.MessageType.Warning,
                    problem + "\n Take a look at the doctor for more information.",
                  )
                )
                CheckDoctor.dismissForever
              },
            )
            .asScala
            .foreach { item =>
              if (item == CheckDoctor.moreInformation) {
                headDoctor.executeRunDoctor()
              } else if (item == CheckDoctor.dismissForever) {
                notification.dismissForever()
              }
            }
        }
      case None =>
        () // All OK.
    }
  }

  private def allTargetIds(): Seq[BuildTargetIdentifier] =
    buildTargets.allBuildTargetIds

  private def selectedBuildToolMessage(): Option[(String, Boolean)] = {
    serviceInfo match {
      case FallbackService => None
      case projectService: ProjectService =>
        val isExplicitChoice =
          projectService.buildTools.current().size > 1
        tables.buildTool.selectedBuildTool().map { value =>
          (s"Build definition is coming from ${value}.", isExplicitChoice)
        }
    }
  }

  private def selectedImportBuildMessage(): Option[String] = {
    import scala.concurrent.duration._
    tables.dismissedNotifications.ImportChanges.whenExpires().map {
      expiration =>
        val whenString =
          if (expiration > 1000.days.toMillis) "forever"
          else "temporarily"
        s"Build import popup on configuration changes has been dismissed $whenString"
    }
  }

  /**
   * @return Selected build server message and also whether or not the server was chosen
   *         explicity. For example, if the user is just using Bloop and never made and
   *         explicit choice to use another server and then come back to Bloop, it's
   *         not explict and won't get the option to reset the choice, since no choice
   *         exists. (Message, Explict Choice)
   */
  private def selectedBuildServerMessage(): (String, Boolean) = {
    serviceInfo match {
      case FallbackService =>
        (s"Using scala-cli for fallback service.", false)
      case projectInfo: ProjectService =>
        val current = projectInfo
          .currentBuildServer()
          .map(s => (s.main.name, s.main.version))
        val chosen = tables.buildServers.selectedServer()

        (current, chosen) match {
          case (Some((name, version)), Some(_)) =>
            (s"Build server currently being used is $name v$version.", true)
          case (Some((name, version)), None) =>
            (s"Build server currently being used is $name v$version.", false)
          case (None, _) =>
            projectInfo.calculateNewBuildServer() match {
              case ResolvedNone =>
                (
                  "No build server found. Try to run the generate-bsp-config command.",
                  false,
                )
              case ResolvedBloop =>
                ("Build server currently being used is Bloop.", false)
              case ResolvedBspOne(details) =>
                (
                  s"Build server currently being used is ${details.getName()}.",
                  false,
                )
              case ResolvedMultiple(_, _) =>
                (
                  "Multiple build servers found for your workspace. Attempt to connect to choose your desired server.",
                  false,
                )
              case RegenerateBspConfig =>
                (
                  "Build server configuration is regenerating. Please wait for it to complete.",
                  false,
                )
            }
        }
    }
  }

  def buildTargetsJson(): DoctorFolderResults = {
    val targetIds = allTargetIds()
    val buildToolHeading = selectedBuildToolMessage().map(_._1)

    val (buildServerHeading, _) = selectedBuildServerMessage()
    val importBuildHeading = selectedImportBuildMessage()
    val header =
      DoctorFolderHeader(
        serviceInfo.getJavaInfo().map(_.print),
        buildToolHeading,
        buildServerHeading,
        importBuildHeading,
        isServerResponsive,
      )
    if (targetIds.isEmpty) {
      DoctorFolderResults(
        folderName,
        header,
        Some(
          List(
            DoctorMessage(
              noBuildTargetsTitle,
              List(noBuildTargetRecOne, noBuildTargetRecTwo),
            )
          )
        ),
        None,
        List.empty,
        getErrorReports(),
      )
    } else {
      val allTargetsInfo = targetIds
        .flatMap(extractTargetInfo)
        .sortBy(f => (f.baseDirectory, f.name, f.dataKind))

      val explanations = List(
        DoctorExplanation.CompilationStatus.toJson(allTargetsInfo),
        DoctorExplanation.Diagnostics.toJson(allTargetsInfo),
        DoctorExplanation.Interactive.toJson(allTargetsInfo),
        DoctorExplanation
          .SemanticDB(problemResolver.isBazelBsp)
          .toJson(allTargetsInfo),
        DoctorExplanation.Debugging.toJson(allTargetsInfo),
        DoctorExplanation.JavaSupport.toJson(allTargetsInfo),
      )

      DoctorFolderResults(
        folderName,
        header,
        None,
        Some(allTargetsInfo),
        explanations,
        getErrorReports(),
      )
    }
  }

  private def getErrorReports(): List[ErrorReportInfo] =
    for {
      provider <- rc.all
      report <- provider.getReports()
    } yield {
      val (name, buildTarget) =
        ReportFileName.getReportNameAndBuildTarget(report)
      ErrorReportInfo(
        name,
        report.timestamp,
        report.toPath.toUri().toString(),
        buildTarget,
        Doctor.getErrorReportSummary(report, workspace).getOrElse(""),
        provider.name,
      )
    }

  private def gotoBuildTargetCommand(
      workspace: AbsolutePath,
      buildTargetName: String,
  ): String =
    goToCommand(
      FileDecoderProvider
        .createBuildTargetURI(
          workspace,
          buildTargetName.bazelEscapedDisplayName,
        )
        .toString
    )

  private def goToCommand(uri: String): String =
    clientConfig
      .commandInHtmlFormat()
      .map(format => {
        val range = new l.Range(
          new l.Position(0, 0),
          new l.Position(0, 0),
        )
        val location = ClientCommands.WindowLocation(uri, range)
        ClientCommands.GotoLocation.toCommandLink(location, format)
      })
      .getOrElse(uri)

  private def zipReports(): Option[String] =
    clientConfig
      .commandInHtmlFormat()
      .map(ServerCommands.ZipReports.toCommandLink(_))

  private def createGithubIssue(): Option[String] =
    clientConfig
      .commandInHtmlFormat()
      .map(ServerCommands.OpenIssue.toCommandLink(_))

  private def resetChoiceCommand(choice: String): String = {
    val param = s"""["$choice"]"""
    s"command:metals.reset-choice?${URLEncoder.encode(param, StandardCharsets.UTF_8.name())}"
  }

  def buildTargetsTable(
      html: HtmlBuilder,
      includeWorkspaceFolderName: Boolean,
      focusOnBuildTarget: Option[String],
  ): Unit = {
    if (includeWorkspaceFolderName) {
      html.element("h2")(_.text(folderName))
    }
    serviceInfo.getJavaInfo().foreach { jdk =>
      html.element("p") { builder =>
        builder.bold("Project's Java: ")
        builder.text(jdk.print)
      }
    }

    selectedBuildToolMessage().foreach { case (msg, explicitChoice) =>
      html.element("p")(
        _.text(msg)
          .optionally(!clientConfig.isHttpEnabled && explicitChoice)(
            _.text(" (")
              .link(resetChoiceCommand(PopupChoiceReset.BuildTool), "Reset")
              .text(")")
          )
      )
    }

    selectedImportBuildMessage().foreach { msg =>
      html.element("p")(
        _.text(msg)
          .optionally(!clientConfig.isHttpEnabled)(
            _.text(" (")
              .link(resetChoiceCommand(PopupChoiceReset.BuildImport), "Reset")
              .text(")")
          )
      )
    }

    isServerResponsive.withFilter(!_).foreach { _ =>
      html.element("p")(_.text(buildServerNotResponsive))
    }

    val (message, explicitChoice) = selectedBuildServerMessage()

    if (explicitChoice) {
      html.element("p")(
        _.text(message)
          .optionally(!clientConfig.isHttpEnabled)(
            _.text(" (")
              .link(resetChoiceCommand(PopupChoiceReset.BuildServer), "Reset")
              .text(")")
          )
      )
    } else {
      html.element("p")(
        _.text(message)
      )
    }

    val targetIds = allTargetIds()
    val errorReports = getErrorReports().groupBy(ErrorReportsGroup.from)
    if (targetIds.isEmpty) {
      html
        .element("p")(
          _.text(noBuildTargetsTitle)
            .element("ul")(
              _.element("li")(
                _.text(noBuildTargetRecOne)
              ).element("li")(
                _.text(noBuildTargetRecTwo)
              )
            )
        )
    } else {
      val allTargetsInfo = targetIds.flatMap(extractTargetInfo)
      html
        .element("table")(
          _.element("thead")(
            _.element("tr")(
              _.element("th")(_.text("Build target"))
                .element("th")(_.text("Type"))
                .element("th")(_.text("Compilation status"))
                .element("th")(_.text("Diagnostics"))
                .element("th")(_.text("Interactive"))
                .element("th")(_.text("Semanticdb"))
                .element("th")(_.text("Debugging"))
                .element("th")(_.text("Java support"))
                .element("th")(_.text("Error reports"))
                .element("th")(_.text("Recommendation"))
            )
          ).element("tbody")(html =>
            buildTargetRows(
              html,
              allTargetsInfo,
              errorReports.collect { case (BuildTarget(name) -> v) =>
                (name -> v)
              },
            )
          )
        )

      // Additional explanations
      DoctorExplanation.CompilationStatus.toHtml(html, allTargetsInfo)
      DoctorExplanation.Diagnostics.toHtml(html, allTargetsInfo)
      DoctorExplanation.Interactive.toHtml(html, allTargetsInfo)
      DoctorExplanation
        .SemanticDB(problemResolver.isBazelBsp)
        .toHtml(html, allTargetsInfo)
      DoctorExplanation.Debugging.toHtml(html, allTargetsInfo)
      DoctorExplanation.JavaSupport.toHtml(html, allTargetsInfo)

      addErrorReportsInfo(html, errorReports, focusOnBuildTarget)
    }
  }

  private def addErrorReportsInfo(
      html: HtmlBuilder,
      errorReports: Map[ErrorReportsGroup, List[ErrorReportInfo]],
      focusOnBuildTarget: Option[String],
  ) = {
    html.element("h2")(_.text("Error reports:"))
    errorReports.toVector
      .sortWith {
        case (BuildTarget(v1) -> _, BuildTarget(v2) -> _) => v1 < v2
        case (v1 -> _, v2 -> _) => v1.orderingNumber < v2.orderingNumber
      }
      .foreach { case (group, reports) =>
        val shouldOpen = focusOnBuildTarget.exists(group.id == _)
        html.element("details", if (shouldOpen) "open" else "")(details =>
          details
            .element("summary", s"id=reports-${group.id}")(
              _.element("b")(
                _.text(s"${group.name} (${reports.length}):")
              )
            )
            .element("table") { table =>
              reports.foreach { report =>
                val reportName = report.name.replaceAll("[_-]", " ")
                val dateTime = dateTimeFormat.format(new Date(report.timestamp))
                table.element("tr")(tr =>
                  tr.element("td")(_.raw(Icons.unicode.folder))
                    .element("td")(_.link(goToCommand(report.uri), reportName))
                    .element("td")(_.text(dateTime))
                    .element("td")(_.text(report.shortSummary))
                )
              }
            }
        )
      }
    for {
      zipReportsCommand <- zipReports()
      createIssueCommand <- createGithubIssue()
    } html.element("p")(
      _.text(
        "You can attach a single error report or a couple or reports in a zip file "
      )
        .link(zipReportsCommand, "(create a zip file from anonymized reports)")
        .text(" to your GitHub issue ")
        .link(createIssueCommand, "(create a github issue)")
        .text(" to help with debugging.")
    )
  }

  private def buildTargetRows(
      html: HtmlBuilder,
      infos: Seq[DoctorTargetInfo],
      errorReports: Map[String, List[ErrorReportInfo]],
  ): Unit = {
    infos
      .sortBy(f => (f.baseDirectory, f.name, f.dataKind))
      .foreach { targetInfo =>
        val center = "style='text-align: center'"
        def addErrorReportText(html: HtmlBuilder) =
          errorReports.getOrElse(targetInfo.name, List.empty) match {
            case Nil => html.text(Icons.unicode.check)
            case _ =>
              html.link(s"#reports-${targetInfo.name}", Icons.unicode.alert)
          }
        html.element("tr")(
          _.element("td")(_.link(targetInfo.gotoCommand, targetInfo.name))
            .element("td")(_.text(targetInfo.targetType))
            .element("td", center)(
              _.text(targetInfo.compilationStatus.explanation)
            )
            .element("td", center)(
              _.text(targetInfo.diagnosticsStatus.explanation)
            )
            .element("td", center)(
              _.text(targetInfo.interactiveStatus.explanation)
            )
            .element("td", center)(_.text(targetInfo.indexesStatus.explanation))
            .element("td", center)(
              _.text(targetInfo.debuggingStatus.explanation)
            )
            .element("td", center)(_.text(targetInfo.javaStatus.explanation))
            .element("td", center)(addErrorReportText)
            .element("td")(_.raw(targetInfo.recommenedFix))
        )
      }
  }

  override def getTargetsInfoForReports(): List[Map[String, String]] =
    allTargetIds()
      .flatMap(extractTargetInfo(_))
      .map(_.toMap(exclude = List("gotoCommand", "buildTarget")))
      .toList

  private def extractTargetInfo(
      targetId: BuildTargetIdentifier
  ): List[DoctorTargetInfo] = {
    val javaTarget = buildTargets.javaTarget(targetId)
    val scalaTarget = buildTargets.scalaTarget(targetId)

    (scalaTarget, javaTarget) match {
      case (Some(scalaTarget), javaTarget) =>
        List(extractScalaTargetInfo(scalaTarget, javaTarget))
      case (None, Some(javaTarget)) =>
        List(extractJavaInfo(javaTarget))
      case _ =>
        Nil
    }
  }

  private def extractCompilationStatus(
      targetId: BuildTargetIdentifier
  ): DoctorStatus = {
    if (diagnostics.hasCompilationErrors(targetId))
      DoctorStatus.error
    else {
      val scalaTargetMaybe = buildTargets.scalaTarget(targetId)

      val dependenciesCompile =
        scalaTargetMaybe.flatMap {
          _.info.getDependencies().asScala.collectFirst { buildId =>
            diagnostics.hasCompilationErrors(buildId)
          }
        }.isEmpty

      if (
        scalaTargetMaybe
          .map(_.isBestEffort)
          .getOrElse(false) && !dependenciesCompile
      ) DoctorStatus.alert
      else DoctorStatus.check
    }
  }

  private def extractJavaInfo(
      javaTarget: JavaTarget
  ): DoctorTargetInfo = {
    val compilationStatus = extractCompilationStatus(javaTarget.info.getId())
    val diagnosticsStatus = DoctorStatus(Icons.unicode.check, isCorrect = true)
    val (javaSupport, javaRecommendation) =
      if (javaTarget.isSemanticdbEnabled)
        (DoctorStatus.check, None)
      else
        (
          DoctorStatus.alert,
          problemResolver.recommendation(javaTarget),
        )

    val canRun = javaTarget.info.getCapabilities().getCanRun()
    val canTest = javaTarget.info.getCapabilities().getCanTest()
    val debugging =
      if (canRun && canTest) DoctorStatus.check
      else DoctorStatus.error
    DoctorTargetInfo(
      javaTarget.displayName,
      gotoBuildTargetCommand(workspace, javaTarget.displayName),
      javaTarget.dataKind,
      Option(javaTarget.baseDirectory),
      "Java",
      compilationStatus,
      diagnosticsStatus,
      DoctorStatus.error,
      javaSupport,
      debugging,
      javaSupport,
      javaRecommendation
        .getOrElse(""),
    )
  }

  private def isServerResponsive: Option[Boolean] =
    serviceInfo match {
      case FallbackService => None
      case projectInfo: ProjectService =>
        projectInfo.bspStatus.isBuildServerResponsive
    }

  private def extractScalaTargetInfo(
      scalaTarget: ScalaTarget,
      javaTarget: Option[JavaTarget],
  ): DoctorTargetInfo = {
    val scalaVersion = scalaTarget.scalaVersion
    val interactive =
      if (mtagsResolver.isSupportedScalaVersion(scalaVersion))
        DoctorStatus.check
      else
        DoctorStatus.error

    val isSemanticdbNeeded = !scalaTarget.isSemanticdbEnabled
    val indexes =
      if (isSemanticdbNeeded) DoctorStatus.error
      else if (scalaTarget.semanticdbFilesPresent())
        DoctorStatus.check
      else DoctorStatus.alert

    val compilationStatus = extractCompilationStatus(scalaTarget.info.getId())

    val recommendedFix = problemResolver
      .recommendation(scalaTarget)
      .orElse {
        if (javaTarget.isEmpty)
          scribe.debug("No javac information found from the build server")
        javaTarget.flatMap(target => problemResolver.recommendation(target))
      }
    val (targetType, diagnosticsStatus) =
      scalaTarget.sbtVersion match {
        case Some(sbt)
            if currentBuildServer().exists(_.mainConnectionIsBloop) =>
          (s"sbt $sbt", DoctorStatus.alert)
        case Some(sbt) =>
          (s"sbt $sbt", DoctorStatus.check)
        case None =>
          (s"Scala $scalaVersion", DoctorStatus.check)
      }
    val (javaSupport, javaRecommendation) = javaTarget match {
      case Some(target) if target.isSemanticdbEnabled =>
        (DoctorStatus.check, None)
      case Some(target) =>
        (
          DoctorStatus.alert,
          problemResolver.recommendation(target),
        )
      case None => (DoctorStatus.alert, None)
    }

    val canRun = scalaTarget.info.getCapabilities().getCanRun()
    val canTest = scalaTarget.info.getCapabilities().getCanTest()
    val debugging =
      if (canRun && canTest && !scalaTarget.isSbt) DoctorStatus.check
      else DoctorStatus.error
    val sbtRecommendation =
      if (scalaTarget.isSbt)
        Some(
          "Diagnostics and debugging for sbt are not supported. This might be improved in future."
        )
      else None
    DoctorTargetInfo(
      scalaTarget.displayName,
      gotoBuildTargetCommand(workspace, scalaTarget.displayName),
      scalaTarget.dataKind,
      Option(scalaTarget.baseDirectory),
      targetType,
      compilationStatus,
      diagnosticsStatus,
      interactive,
      indexes,
      debugging,
      javaSupport,
      recommendedFix
        .orElse(javaRecommendation)
        .orElse(sbtRecommendation)
        .getOrElse(""),
    )
  }
  private val noBuildTargetsTitle =
    s"${Icons.unicode.alert} No build targets were detected in this workspace so most functionality won't work."
  private val noBuildTargetRecOne =
    s"Make sure the workspace directory '$workspace' matches the root of your build."
  private val noBuildTargetRecTwo =
    "Try removing the directories .metals/ and .bloop/, then restart metals And import the build again."
  private val buildServerNotResponsive =
    "Build server is not responding."
  private val dateTimeFormat = new SimpleDateFormat("dd MMM HH:mm:ss")
}

case class DoctorVisibilityDidChangeParams(
    visible: Boolean
)

object Doctor {

  /**
   * Extracts the short summary [[scala.meta.internal.metals.Report.shortSummary]]
   * from an error report file.
   * @param file error report file
   * @param root workspace folder root
   * @return short summary or nothing if no summary or error while reading the file
   */
  def getErrorReportSummary(
      file: TimestampedFile,
      root: AbsolutePath,
  ): Option[String] = {
    def decode(text: String) =
      text.replace(StdReportContext.WORKSPACE_STR, root.toString())
    for {
      lines <- Try(Files.readAllLines(file.toPath).asScala.toList).toOption
      index = lines.lastIndexWhere(_.startsWith(Report.summaryTitle))
      if index >= 0
    } yield lines
      .drop(index + 1)
      .dropWhile(_ == "")
      .map(decode)
      .mkString("\n")
  }
}

sealed trait ErrorReportsGroup {
  def orderingNumber: Int
  def id: String
  def name: String
}

case object Bloop extends ErrorReportsGroup {
  def orderingNumber = 1
  def id: String = "bloop"
  def name: String = "Bloop error reports"
}
case class BuildTarget(name: String) extends ErrorReportsGroup {
  def orderingNumber = 2
  def id: String = name
}
case object Other extends ErrorReportsGroup {
  def orderingNumber = 3
  def id: String = "other"
  def name: String = "Other error reports"
}

object ErrorReportsGroup {
  def from(info: ErrorReportInfo): ErrorReportsGroup = {
    info.buildTarget match {
      case Some(bt) => BuildTarget(bt)
      case None if info.errorReportType == "bloop" => Bloop
      case _ => Other
    }
  }
}

trait TargetsInfoProvider {
  def getTargetsInfoForReports(): List[Map[String, String]]
}

sealed trait MetalsServiceInfo {
  def getJavaInfo(): Option[JavaInfo]
}
object MetalsServiceInfo {
  object FallbackService extends MetalsServiceInfo {
    def getJavaInfo(): Option[JavaInfo] = None
  }

  case class ProjectService(
      currentBuildServer: () => Option[BspSession],
      calculateNewBuildServer: () => BspResolvedResult,
      buildTools: BuildTools,
      bspStatus: ConnectionBspStatus,
      javaInfo: () => Option[JavaInfo],
  ) extends MetalsServiceInfo {
    def getJavaInfo(): Option[JavaInfo] = javaInfo()
  }
}
