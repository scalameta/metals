package scala.meta.internal.metals

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext

import scala.meta.internal.bsp.BspResolvedResult
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ResolvedBloop
import scala.meta.internal.bsp.ResolvedBspOne
import scala.meta.internal.bsp.ResolvedMultiple
import scala.meta.internal.bsp.ResolvedNone
import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.config.DoctorFormat
import scala.meta.internal.troubleshoot.ProblemResolver
import scala.meta.io.AbsolutePath

/**
 * Helps the user figure out what is mis-configured in the build through the "Run doctor" command.
 *
 * At the moment, the doctor only validates that SemanticDB is enabled for all projects.
 */
final class Doctor(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    currentBuildServer: () => Option[BspSession],
    calculateNewBuildServer: () => BspResolvedResult,
    httpServer: () => Option[MetalsHttpServer],
    tables: Tables,
    clientConfig: ClientConfiguration,
    mtagsResolver: MtagsResolver
)(implicit ec: ExecutionContext) {
  private val hasProblems = new AtomicBoolean(false)
  private val problemResolver =
    new ProblemResolver(
      workspace,
      mtagsResolver,
      currentBuildServer,
      clientConfig.isCommandInHtmlSupported()
    )

  /**
   * Returns a full HTML page for the HTTP client.
   */
  def problemsHtmlPage(url: String): String = {
    val livereload = Urls.livereload(url)
    HtmlBuilder()
      .page(
        doctorTitle,
        List(livereload, HtmlBuilder.htmlCSS),
        HtmlBuilder.bodyStyle
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
      ClientCommands.RunDoctor,
      server => {
        Urls.openBrowser(server.address + "/doctor")
      }
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
      ClientCommands.ReloadDoctor,
      server => {
        server.reload()
      }
    )
  }

  private def executeDoctor(
      clientCommand: ParametrizedCommand[String],
      onServer: MetalsHttpServer => Unit
  ): Unit = {
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

  /**
   * Checks if there are any potential problems and if any, notifies the user.
   */
  def check(): Unit = {
    val summary = problemResolver.problemMessage(allTargets())
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

  def allTargets(): List[ScalaTarget] = buildTargets.all.toList

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
              false
            )
          case ResolvedBloop =>
            ("Build server currently being used is Bloop.", false)
          case ResolvedBspOne(details) =>
            (
              s"Build server currently being used is ${details.getName()}.",
              false
            )
          case ResolvedMultiple(_, _) =>
            (
              "Multiple build servers found for your workspace. Attempt to connect to choose your desired server.",
              false
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

  private def buildTargetsJson(): String = {
    val targets = allTargets()
    val buildToolHeading = selectedBuildToolMessage()
    val (buildServerHeading, _) = selectedBuildServerMessage()
    val importBuildHeading = selectedImportBuildMessage()
    val heading =
      List(
        buildToolHeading,
        Some(buildServerHeading),
        importBuildHeading,
        Some(doctorHeading)
      ).flatten
        .mkString("\n\n")

    val results = if (targets.isEmpty) {
      DoctorResults(
        doctorTitle,
        heading,
        Some(
          List(
            DoctorMessage(
              noBuildTargetsTitle,
              List(noBuildTargetRecOne, noBuildTargetRecTwo)
            )
          )
        ),
        None
      ).toJson

    } else {
      val targetResults =
        targets.sortBy(_.baseDirectory).map(extractTargetInfo)
      DoctorResults(doctorTitle, heading, None, Some(targetResults)).toJson
    }
    ujson.write(results)
  }

  private def resetChoiceCommand(choice: String): String = {
    val param = s"""["$choice"]"""
    s"command:metals.reset-choice?${URLEncoder.encode(param)}"
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

    html
      .element("p")(
        _.text(doctorHeading)
      )

    val targets = allTargets()
    if (targets.isEmpty) {
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
      html
        .element("table")(
          _.element("thead")(
            _.element("tr")(
              _.element("th")(_.text("Build target"))
                .element("th")(_.text("Scala"))
                .element("th")(_.text("Diagnostics"))
                .element("th")(_.text("Goto definition"))
                .element("th")(_.text("Completions"))
                .element("th")(_.text("Find references"))
                .element("th")(_.text("Recommendation"))
            )
          ).element("tbody")(html => buildTargetRows(html, targets))
        )
    }
  }

  private def buildTargetRows(
      html: HtmlBuilder,
      targets: List[ScalaTarget]
  ): Unit = {
    targets.sortBy(_.baseDirectory).foreach { target =>
      val targetInfo = extractTargetInfo(target)
      val center = "style='text-align: center'"
      html.element("tr")(
        _.element("td")(_.text(targetInfo.name))
          .element("td")(_.text(targetInfo.scalaVersion))
          .element("td", center)(_.text(Icons.unicode.check))
          .element("td", center)(_.text(targetInfo.definitionStatus))
          .element("td", center)(_.text(targetInfo.completionsStatus))
          .element("td", center)(_.text(targetInfo.referencesStatus))
          .element("td")(
            _.raw(targetInfo.recommenedFix)
          )
      )
    }
  }

  private def extractTargetInfo(target: ScalaTarget) = {
    val scalaVersion = target.scalaVersion
    val definition: String =
      if (mtagsResolver.isSupportedScalaVersion(scalaVersion)) {
        Icons.unicode.check
      } else {
        Icons.unicode.alert
      }
    val completions: String = definition
    val isSemanticdbNeeded = !target.isSemanticdbEnabled
    val references: String =
      if (isSemanticdbNeeded) {
        Icons.unicode.alert
      } else {
        Icons.unicode.check
      }
    val recommenedFix = problemResolver.recommendation(target)
    DoctorTargetInfo(
      target.displayName,
      scalaVersion,
      definition,
      completions,
      references,
      recommenedFix
    )
  }

  private val doctorTitle = "Metals Doctor"
  private val doctorHeading =
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
