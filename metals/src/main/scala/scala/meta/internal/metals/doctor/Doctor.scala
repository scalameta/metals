package scala.meta.internal.metals.doctor

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Try

import scala.meta.internal.bsp.BspResolvedResult
import scala.meta.internal.bsp.BspSession
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
import scala.meta.internal.metals.JavaTarget
import scala.meta.internal.metals.JdkVersion
import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.PopupChoiceReset
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ReportFileName
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
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
    currentBuildServer: () => Option[BspSession],
    calculateNewBuildServer: () => BspResolvedResult,
    tables: Tables,
    clientConfig: ClientConfiguration,
    mtagsResolver: MtagsResolver,
    javaHome: () => Option[String],
    maybeJDKVersion: Option[JdkVersion],
    folderName: String,
    buildTools: BuildTools,
)(implicit ec: ExecutionContext, rc: ReportContext) {
  private val hasProblems = new AtomicBoolean(false)
  private val problemResolver =
    new ProblemResolver(
      workspace,
      mtagsResolver,
      currentBuildServer,
      javaHome,
      () => clientConfig.isTestExplorerProvider(),
      maybeJDKVersion,
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
    val scalaTargets = buildTargets.allScala.toList
    val javaTargets = buildTargets.allJava.toList
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
          languageClient.showMessageRequest(params).asScala.foreach { item =>
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
    val isExplicitChoice = buildTools.loadSupported().length > 1
    tables.buildTool.selectedBuildTool().map { value =>
      (s"Build definition is coming from ${value}.", isExplicitChoice)
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
    val current = currentBuildServer().map(s => (s.main.name, s.main.version))
    val chosen = tables.buildServers.selectedServer()

    (current, chosen) match {
      case (Some((name, version)), Some(_)) =>
        (s"Build server currently being used is $name v$version.", true)
      case (Some((name, version)), None) =>
        (s"Build server currently being used is $name v$version.", false)
      case (None, _) =>
        calculateNewBuildServer() match {
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
        DoctorExplanation.SemanticDB.toJson(allTargetsInfo),
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

  private def getErrorReports(): List[ErrorReportInfo] = {
    def getBuildTarget(path: Path) =
      for {
        lines <- Try(Files.readAllLines(path).asScala.toList).toOption
        filePath <- lines.collectFirst {
          case line if line.startsWith("file:") =>
            line
              .strip()
              .replace(StdReportContext.WORKSPACE_STR, workspace.toString())
              .toAbsolutePath
        }
        buildTargetId <- buildTargets.inverseSources(filePath)
        name <- buildTargets
          .scalaTarget(buildTargetId)
          .map(_.displayName)
          .orElse(buildTargets.javaTarget(buildTargetId).map(_.displayName))
      } yield name
    rc.getReports().map {
      case TimestampedFile(file, timestamp) =>
        ErrorReportInfo(
          ReportFileName.getReportName(file),
          timestamp,
          file.toPath.toUri().toString(),
          getBuildTarget(file.toPath),
        )
    }
  }

  private def gotoBuildTargetCommand(
      workspace: AbsolutePath,
      buildTargetName: String,
  ): String =
    goToCommand(
      FileDecoderProvider
        .createBuildTargetURI(workspace, buildTargetName)
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

  private def resetChoiceCommand(choice: String): String = {
    val param = s"""["$choice"]"""
    s"command:metals.reset-choice?${URLEncoder.encode(param, StandardCharsets.UTF_8.name())}"
  }

  def buildTargetsTable(
      html: HtmlBuilder,
      includeWorkspaceFolderName: Boolean,
  ): Unit = {
    if (includeWorkspaceFolderName) {
      html.element("h2")(_.text(folderName))
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
    val errorReports = getErrorReports().groupBy(_.buildTarget)
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
            buildTargetRows(html, allTargetsInfo, errorReports)
          )
        )

      // Additional explanations
      DoctorExplanation.CompilationStatus.toHtml(html, allTargetsInfo)
      DoctorExplanation.Diagnostics.toHtml(html, allTargetsInfo)
      DoctorExplanation.Interactive.toHtml(html, allTargetsInfo)
      DoctorExplanation.SemanticDB.toHtml(html, allTargetsInfo)
      DoctorExplanation.Debugging.toHtml(html, allTargetsInfo)
      DoctorExplanation.JavaSupport.toHtml(html, allTargetsInfo)

      addErrorReportsInfo(html, errorReports)
    }
  }

  private def addErrorReportsInfo(
      html: HtmlBuilder,
      errorReports: Map[Option[String], List[ErrorReportInfo]],
  ) = {
    html.element("h2")(_.text("Error reports:"))
    errorReports.foreach { case (optBuildTarget, reports) =>
      def name(default: String) = optBuildTarget.getOrElse(default)
      html.element("div")(div =>
        div
          .element("p", s"id=reports-${name("other")}")(
            _.element("b")(_.text(s"${name("Other error reports")}:"))
          )
          .element("table") { table =>
            reports.foreach { report =>
              val reportName = report.name.replaceAll("[_-]", " ")
              val dateTime = dateTimeFormat.format(new Date(report.timestamp))
              table.element("tr")(tr =>
                tr.element("td")(_.raw(Icons.unicode.folder))
                  .element("td")(_.text(reportName))
                  .element("td")(_.text(dateTime))
                  .element("td")(_.link(goToCommand(report.uri), "(see report)"))
              )
            }
          }
      )
    }
  }

  private def buildTargetRows(
      html: HtmlBuilder,
      infos: Seq[DoctorTargetInfo],
      errorReports: Map[Option[String], List[ErrorReportInfo]],
  ): Unit = {
    infos
      .sortBy(f => (f.baseDirectory, f.name, f.dataKind))
      .foreach { targetInfo =>
        val center = "style='text-align: center'"
        def addErrorReportText(html: HtmlBuilder) =
          errorReports.getOrElse(Some(targetInfo.name), List.empty) match {
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

  def getTargetsInfoForReports(): List[Map[String, String]] =
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
    else
      DoctorStatus.check
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
      javaTarget.baseDirectory,
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
    currentBuildServer().flatMap { conn =>
      val isResponsiveFuture = conn.main.isBuildServerResponsive
      Try(Await.result(isResponsiveFuture, Duration("1s"))).toOption.flatten
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
      if (isSemanticdbNeeded) DoctorStatus.error else DoctorStatus.check

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
      case None if scalaTarget.isAmmonite => (DoctorStatus.info, None)
      case None => (DoctorStatus.alert, None)
    }

    val canRun = scalaTarget.info.getCapabilities().getCanRun()
    val canTest = scalaTarget.info.getCapabilities().getCanTest()
    val debugging =
      if (canRun && canTest && !scalaTarget.isSbt) DoctorStatus.check
      else DoctorStatus.error
    val sbtRecommendation =
      if (scalaTarget.isSbt)
        Some("Diagnostics and debugging for sbt are not supported currently.")
      else if (scalaTarget.isAmmonite)
        Some("Debugging for Ammonite are not supported currently.")
      else None
    DoctorTargetInfo(
      scalaTarget.displayName,
      gotoBuildTargetCommand(workspace, scalaTarget.displayName),
      scalaTarget.dataKind,
      scalaTarget.baseDirectory,
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
