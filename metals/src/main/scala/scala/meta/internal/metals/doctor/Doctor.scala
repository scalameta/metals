package scala.meta.internal.metals.doctor

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext

import scala.meta.internal.bsp.BspResolvedResult
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ResolvedBloop
import scala.meta.internal.bsp.ResolvedBspOne
import scala.meta.internal.bsp.ResolvedMultiple
import scala.meta.internal.bsp.ResolvedNone
import scala.meta.internal.metals.BuildInfo
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
import scala.meta.internal.metals.MetalsHttpServer
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ParametrizedCommand
import scala.meta.internal.metals.PopupChoiceReset
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.Urls
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.config.DoctorFormat
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
    httpServer: () => Option[MetalsHttpServer],
    tables: Tables,
    clientConfig: ClientConfiguration,
    mtagsResolver: MtagsResolver,
    javaHome: () => Option[String],
    maybeJDKVersion: Option[JdkVersion],
)(implicit ec: ExecutionContext) {
  private val isVisible = new AtomicBoolean(false)
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

  def onVisibilityDidChange(newState: Boolean): Unit = {
    isVisible.set(newState)
  }

  /**
   * Returns a full HTML page for the HTTP client.
   */
  def problemsHtmlPage(url: String): String = {
    val livereload = Urls.livereload(url)
    HtmlBuilder()
      .page(
        doctorTitle,
        List(livereload, HtmlBuilder.htmlCSS),
        HtmlBuilder.bodyStyle,
      ) { html =>
        html.section("Build targets", buildTargetsTable)
      }
      .render
  }

  /**
   * Executes the "Run doctor" server command.
   */
  def executeRunDoctor(): Unit = {
    executeDoctor(
      clientCommand = ClientCommands.RunDoctor,
      onServer = server => {
        Urls.openBrowser(server.address + "/doctor")
      },
    )
  }

  def isUnsupportedBloopVersion(): Boolean =
    problemResolver.isUnsupportedBloopVersion()

  /**
   * Executes the "Reload doctor" server command.
   */
  private def executeReloadDoctor(summary: Option[String]): Unit = {
    val hasProblemsNow = summary.isDefined
    if (hasProblems.get() && !hasProblemsNow) {
      hasProblems.set(false)
      languageClient.showMessage(CheckDoctor.problemsFixed)
    }
    executeRefreshDoctor()
  }

  def executeRefreshDoctor(): Unit = {
    executeDoctor(
      clientCommand = ClientCommands.ReloadDoctor,
      onServer = server => {
        server.reload()
      },
    )
  }

  /**
   * @param clientCommand RunDoctor or ReloadDoctor
   * @param onServer piece of logic that will be executed when http server is enabled
   */
  private def executeDoctor(
      clientCommand: ParametrizedCommand[String],
      onServer: MetalsHttpServer => Unit,
  ): Unit = {
    val isVisibilityProvider = clientConfig.isDoctorVisibilityProvider()
    val shouldDisplay = isVisibilityProvider && isVisible.get()
    if (shouldDisplay || !isVisibilityProvider) {
      if (
        clientConfig.isExecuteClientCommandProvider && !clientConfig.isHttpEnabled
      ) {
        val output = clientConfig.doctorFormat match {
          case DoctorFormat.Json => buildTargetsJson()
          case DoctorFormat.Html => buildTargetsHtml()
        }
        val params = clientCommand.toExecuteCommandParams(output)
        languageClient.metalsExecuteClientCommand(params)
      } else {
        httpServer() match {
          case Some(server) =>
            onServer(server)
          case None =>
            scribe.warn(
              "Unable to run doctor. Make sure `isHttpEnabled` is set to `true`."
            )
        }
      }
    }
  }

  /**
   * Checks if there are any potential problems and if any, notifies the user.
   */
  def check(): Unit = {
    val scalaTargets = buildTargets.allScala.toList
    val javaTargets = buildTargets.allJava.toList
    val summary = problemResolver.problemMessage(scalaTargets, javaTargets)
    executeReloadDoctor(summary)
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
              onVisibilityDidChange(true)
              executeRunDoctor()
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

  private def selectedBuildToolMessage(): Option[String] = {
    tables.buildTool.selectedBuildTool().map { value =>
      s"Build definition is coming from ${value}."
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

  private def buildTargetsHtml(): String = {
    new HtmlBuilder()
      .element("h1")(_.text(doctorTitle))
      .call(buildTargetsTable)
      .render
  }

  private def getJdkInfo(): Option[String] =
    for {
      version <- Option(System.getProperty("java.version"))
      vendor <- Option(System.getProperty("java.vendor"))
      home <- Option(System.getProperty("java.home"))
    } yield s"$version from $vendor located at $home"

  private def buildTargetsJson(): String = {
    val targetIds = allTargetIds()
    val buildToolHeading = selectedBuildToolMessage()

    val (buildServerHeading, _) = selectedBuildServerMessage()
    val importBuildHeading = selectedImportBuildMessage()
    val jdkInfo = getJdkInfo().map(info => s"$jdkVersionTitle$info")
    val serverInfo = s"$serverVersionTitle${BuildInfo.metalsVersion}"
    val header =
      DoctorHeader(
        buildToolHeading,
        buildServerHeading,
        importBuildHeading,
        jdkInfo,
        serverInfo,
        buildTargetDescription,
      )
    val results = if (targetIds.isEmpty) {
      DoctorResults(
        doctorTitle,
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
      ).toJson
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

      DoctorResults(
        doctorTitle,
        header,
        None,
        Some(allTargetsInfo),
        explanations,
      ).toJson
    }
    ujson.write(results)
  }

  private def gotoBuildTargetCommand(
      workspace: AbsolutePath,
      buildTargetName: String,
  ): String = {
    val uriAsStr = FileDecoderProvider
      .createBuildTargetURI(workspace, buildTargetName)
      .toString
    clientConfig
      .commandInHtmlFormat()
      .map(format => {
        val range = new l.Range(
          new l.Position(0, 0),
          new l.Position(0, 0),
        )
        val location = ClientCommands.WindowLocation(uriAsStr, range)
        ClientCommands.GotoLocation.toCommandLink(location, format)
      })
      .getOrElse(uriAsStr)
  }

  private def resetChoiceCommand(choice: String): String = {
    val param = s"""["$choice"]"""
    s"command:metals.reset-choice?${URLEncoder.encode(param, StandardCharsets.UTF_8.name())}"
  }

  private def buildTargetsTable(html: HtmlBuilder): Unit = {
    selectedBuildToolMessage().foreach { msg =>
      html.element("p")(
        _.text(msg)
          .optionally(!clientConfig.isHttpEnabled)(
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

    val jdkInfo = getJdkInfo()

    jdkInfo.foreach { jdkMsg =>
      html.element("p") { builder =>
        builder.bold(jdkVersionTitle)
        builder.text(jdkMsg)
      }
    }

    html.element("p") { builder =>
      builder.bold(serverVersionTitle)
      builder.text(BuildInfo.metalsVersion)
    }

    html
      .element("p")(
        _.text(buildTargetDescription)
      )

    val targetIds = allTargetIds()
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
                .element("th")(_.text("Recommendation"))
            )
          ).element("tbody")(html => buildTargetRows(html, allTargetsInfo))
        )

      // Additional explanations
      DoctorExplanation.CompilationStatus.toHtml(html, allTargetsInfo)
      DoctorExplanation.Diagnostics.toHtml(html, allTargetsInfo)
      DoctorExplanation.Interactive.toHtml(html, allTargetsInfo)
      DoctorExplanation.SemanticDB.toHtml(html, allTargetsInfo)
      DoctorExplanation.Debugging.toHtml(html, allTargetsInfo)
      DoctorExplanation.JavaSupport.toHtml(html, allTargetsInfo)
    }
  }

  private def buildTargetRows(
      html: HtmlBuilder,
      infos: Seq[DoctorTargetInfo],
  ): Unit = {
    infos
      .sortBy(f => (f.baseDirectory, f.name, f.dataKind))
      .foreach { targetInfo =>
        val center = "style='text-align: center'"
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
            .element("td")(_.raw(targetInfo.recommenedFix))
        )
      }
  }

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
          problemResolver.recommendation(javaTarget, scalaTarget = None),
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
        javaTarget.flatMap(target =>
          problemResolver.recommendation(target, Some(scalaTarget))
        )
      }
    val (targetType, diagnosticsStatus) =
      scalaTarget.sbtVersion match {
        case Some(sbt) =>
          (s"sbt $sbt", DoctorStatus.alert)
        case None =>
          (s"Scala $scalaVersion", DoctorStatus.check)
      }
    val (javaSupport, javaRecommendation) = javaTarget match {
      case Some(target) if target.isSemanticdbEnabled =>
        (DoctorStatus.check, None)
      case Some(target) =>
        (
          DoctorStatus.alert,
          problemResolver.recommendation(target, Some(scalaTarget)),
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

  private val doctorTitle = "Metals Doctor"
  private val jdkVersionTitle = "Metals Java: "
  private val serverVersionTitle = "Metals Server version: "
  private val buildTargetDescription =
    "These are the installed build targets for this workspace. " +
      "One build target corresponds to one classpath. For example, normally one sbt project maps to " +
      "two build targets: main and test."
  private val noBuildTargetsTitle =
    s"${Icons.unicode.alert} No build targets were detected in this workspace so most functionality won't work."
  private val noBuildTargetRecOne =
    s"Make sure the workspace directory '$workspace' matches the root of your build."
  private val noBuildTargetRecTwo =
    "Try removing the directories .metals/ and .bloop/, then restart metals And import the build again."
}

case class DoctorVisibilityDidChangeParams(
    visible: Boolean
)
