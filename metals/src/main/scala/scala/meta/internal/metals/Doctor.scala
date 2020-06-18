package scala.meta.internal.metals

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ExecuteCommandParams

/**
 * Helps the user figure out what is mis-configured in the build through the "Run doctor" command.
 *
 * At the moment, the doctor only validates that SemanticDB is enabled for all projects.
 */
final class Doctor(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    httpServer: () => Option[MetalsHttpServer],
    tables: Tables,
    clientConfig: ClientConfiguration
)(implicit ec: ExecutionContext) {
  private val hasProblems = new AtomicBoolean(false)
  private var bspServerName: Option[String] = None
  private var bspServerVersion: Option[String] = None

  def isUnsupportedBloopVersion(serverVersion: String): Boolean = {
    bspServerName.contains("Bloop") && !SemVer.isCompatibleVersion(
      BuildInfo.bloopVersion,
      serverVersion
    )
  }

  /**
   * Returns a full HTML page for the HTTP client. */
  def problemsHtmlPage(url: String): String = {
    val livereload = Urls.livereload(url)
    new HtmlBuilder()
      .page(doctorTitle, livereload) { html =>
        html.section("Build targets", buildTargetsTable)
      }
      .render
  }

  /**
   * Executes the "Run doctor" server command. */
  def executeRunDoctor(): Unit = {
    executeDoctor(
      ClientCommands.RunDoctor,
      server => {
        Urls.openBrowser(server.address + "/doctor")
      }
    )
  }

  /**
   * Executes the "Reload doctor" server command. */
  private def executeReloadDoctor(summary: Option[String]): Unit = {
    val hasProblemsNow = summary.isDefined
    if (hasProblems.get() && !hasProblemsNow) {
      hasProblems.set(false)
      languageClient.showMessage(CheckDoctor.problemsFixed)
    }
    executeDoctor(
      ClientCommands.ReloadDoctor,
      server => {
        server.reload()
      }
    )
  }

  private def executeDoctor(
      clientCommand: Command,
      onServer: MetalsHttpServer => Unit
  ): Unit = {
    if (
      clientConfig.isExecuteClientCommandProvider && !clientConfig.isHttpEnabled
    ) {
      val output =
        if (clientConfig.doctorFormatIsJson)
          buildTargetsJson()
        else buildTargetsHtml()
      val params = new ExecuteCommandParams(
        clientCommand.id,
        List(output: AnyRef).asJava
      )
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
   * Checks if there are any potential problems and if any, notifies the user. */
  def check(serverName: String, serverVersion: String): Unit = {
    bspServerName = Some(serverName)
    bspServerVersion = Some(serverVersion)
    val summary = problemSummary
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

  private def recommendation(
      scalaVersion: String,
      isSemanticdbEnabled: Boolean,
      scala: ScalaTarget
  ): String = {
    def isMaven: Boolean = workspace.resolve("pom.xml").isFile
    def hint() =
      if (isMaven) {
        val website =
          if (clientConfig.doctorFormatIsJson)
            "Metals Website - https://scalameta.org/metals/docs/build-tools/maven.html"
          else
            "<a href=https://scalameta.org/metals/docs/build-tools/maven.html>Metals website</a>"
        "enable SemanticDB following instructions on the " + website
      } else s"run 'Build import' to enable code navigation."

    if (!isSemanticdbEnabled) {
      if (bspServerVersion.exists(isUnsupportedBloopVersion)) {
        s"""|The installed Bloop server version is ${bspServerVersion.get} while Metals requires at least Bloop version ${BuildInfo.bloopVersion},
            |To fix this problem please update your Bloop server.""".stripMargin
      } else if (
        isSupportedScalaVersion(
          scalaVersion
        )
      ) {
        hint.capitalize
      } else if (isSupportedScalaBinaryVersion(scalaVersion)) {
        val recommended = recommendedVersion(scalaVersion)
        val isRecommenedVersionNewer =
          SemVer.isCompatibleVersion(scalaVersion, recommended)
        if (isRecommenedVersionNewer) {
          s"Upgrade to Scala $recommended and " + hint
        } else {
          s"Scala $scalaVersion is not yet supported"
        }

      } else {
        val versionToUpgradeTo =
          if (ScalaVersions.isScala3Version(scalaVersion)) {
            s"Scala ${BuildInfo.scala3}"
          } else {
            s"Scala ${BuildInfo.scala213} or ${BuildInfo.scala212}"
          }
        s"Code navigation is not supported for this compiler version, change to " + versionToUpgradeTo + " and " + hint
      }
    } else {
      val messages = ListBuffer.empty[String]
      if (ScalaVersions.isDeprecatedScalaVersion(scalaVersion)) {
        messages += s"This Scala version might not be supported in upcoming versions of Metals, " +
          s"please upgrade to Scala ${recommendedVersion(scalaVersion)}."
      } else if (!isLatestScalaVersion(scalaVersion)) {
        messages += s"Upgrade to Scala ${recommendedVersion(scalaVersion)} to enjoy the latest compiler improvements."
      }
      if (!scala.isSourcerootDeclared) {
        messages += s"Add the compiler option ${workspace.sourcerootOption} to ensure code navigation works."
      }
      messages.toList match {
        case Nil => ""
        case head :: Nil => head
        case _ =>
          val html = new HtmlBuilder()
          html.unorderedList(messages)(html.text).toString
      }
    }
  }

  private def problemSummary: Option[String] = {

    def message(
        filter: String => Boolean,
        apply: Iterable[String] => String
    ): Option[String] = {
      val versions = (for {
        target <- allTargets.toIterator
        if filter(target.scalaVersion)
      } yield target.scalaVersion).toSet

      if (versions.nonEmpty) {
        Some(apply(versions))
      } else {
        None
      }
    }

    message(
      ScalaVersions.isFutureVersion,
      Messages.FutureScalaVersion.message
    ).orElse {
      message(
        ver => !ScalaVersions.isSupportedScalaVersion(ver),
        Messages.UnsupportedScalaVersion.message
      )
    }.orElse {
      possiblyMissingSemanticDB
    }.orElse {
      message(
        ScalaVersions.isDeprecatedScalaVersion,
        Messages.DeprecatedScalaVersion.message
      )
    }
  }

  private def possiblyMissingSemanticDB: Option[String] = {
    val targets = allTargets()
    val isMissingSemanticdb = targets.filter(!_.isSemanticdbEnabled)
    val count = isMissingSemanticdb.length
    val isAllProjects = count == targets.size
    if (isMissingSemanticdb.isEmpty) {
      None
    } else if (isAllProjects) {
      Some(CheckDoctor.allProjectsMisconfigured)
    } else if (count == 1) {
      val name = isMissingSemanticdb.head.displayName
      Some(CheckDoctor.singleMisconfiguredProject(name))
    } else {
      Some(CheckDoctor.multipleMisconfiguredProjects(count))
    }
  }

  def allTargets(): List[ScalaTarget] = buildTargets.all.toList

  private def buildTargetsHtml(): String = {
    new HtmlBuilder()
      .element("h1")(_.text(doctorTitle))
      .call(buildTargetsTable)
      .render
  }

  private def buildTargetsJson(): String = {
    val targets = allTargets()
    val heading = tables.buildTool.selectedBuildTool() match {
      case Some(value) =>
        doctorHeading + s"\n\nYour ${value} build definition has been imported."
      case None => doctorHeading
    }

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

  private def buildTargetsTable(html: HtmlBuilder): Unit = {
    html
      .element("p")(
        _.text(doctorHeading)
      )

    tables.buildTool.selectedBuildTool() match {
      case Some(value) =>
        html.element("p")(
          _.text(s"Your ${value} build definition has been imported.")
        )
      case None => ()
    }

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
      if (ScalaVersions.isSupportedScalaVersion(scalaVersion)) {
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
    val recommenedFix =
      recommendation(scalaVersion, target.isSemanticdbEnabled, target)
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
