package scala.meta.internal.metals

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.ExecuteCommandParams
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions._
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.BuildTool

/**
 * Helps the user figure out what is mis-configured in the build through the "Run doctor" command.
 *
 * At the moment, the doctor only validates that SemanticDB is enabled for all projects.
 */
final class Doctor(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    config: MetalsServerConfig,
    languageClient: MetalsLanguageClient,
    httpServer: () => Option[MetalsHttpServer],
    tables: Tables,
    messages: Messages
)(implicit ec: ExecutionContext) {
  private val hasProblems = new AtomicBoolean(false)
  private var bspServerName: Option[String] = None
  private var bspServerVersion: Option[String] = None

  /** Returns a full HTML page for the HTTP client. */
  def problemsHtmlPage(url: String): String = {
    val livereload = Urls.livereload(url)
    new HtmlBuilder()
      .page("Metals Doctor", livereload) { html =>
        html.section("Build targets", buildTargetsTable)
      }
      .render
  }

  /** Executes the "Run doctor" server command. */
  def executeRunDoctor(): Unit = {
    executeDoctor(ClientCommands.RunDoctor, server => {
      Urls.openBrowser(server.address + "/doctor")
    })
  }

  /** Executes the "Reload doctor" server command. */
  private def executeReloadDoctor(summary: Option[String]): Unit = {
    val hasProblemsNow = summary.isDefined
    if (hasProblems.get() && !hasProblemsNow) {
      hasProblems.set(false)
      languageClient.showMessage(CheckDoctor.problemsFixed)
    }
    executeDoctor(ClientCommands.ReloadDoctor, server => {
      server.reload()
    })
  }

  private def executeDoctor(
      clientCommand: Command,
      onServer: MetalsHttpServer => Unit
  ): Unit = {
    if (config.executeClientCommand.isOn) {
      val html = buildTargetsHtml()
      val params = new ExecuteCommandParams(
        clientCommand.id,
        List(html: AnyRef).asJava
      )
      languageClient.metalsExecuteClientCommand(params)
    } else {
      httpServer() match {
        case Some(server) =>
          onServer(server)
        case None =>
          scribe.warn(
            "Unable to run doctor. To fix this problem enable -Dmetals.http=true"
          )
      }
    }
  }

  /** Checks if there are any potential problems and if any, notifies the user. */
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

    val minimumBloopVersion = "1.3.3"
    def isUnsupportedBloopVersion =
      bspServerVersion.exists(
        version =>
          !BuildTool.isCompatibleVersion(
            minimumBloopVersion,
            version
          )
      )

    def isMaven: Boolean = workspace.resolve("pom.xml").isFile
    def hint() =
      if (isMaven)
        "enable SemanticDB following instructions on the " +
          "<a href=https://scalameta.org/metals/docs/build-tools/maven.html>Metals website</a>"
      else s"run 'Build import' to enable code navigation."

    if (!isSemanticdbEnabled) {
      if (bspServerName.contains("Bloop") && isUnsupportedBloopVersion) {
        s"""|The installed Bloop server version is ${bspServerVersion.get} while Metals requires at least Bloop version $minimumBloopVersion,
            |To fix this problem please update your Bloop server.""".stripMargin
      } else if (isSupportedScalaVersion(
          scalaVersion
        )) {
        hint().capitalize
      } else if (isSupportedScalaBinaryVersion(scalaVersion)) {
        s"Upgrade to Scala ${recommendedVersion(scalaVersion)} and " + hint()
      } else {
        s"Code navigation is not supported for this compiler version, upgrade to " +
          s"Scala ${BuildInfo.scala212} or ${BuildInfo.scala211} and " +
          s"run 'Build import' to enable code navigation."
      }
    } else {
      val messages = ListBuffer.empty[String]
      if (ScalaVersions.isDeprecatedScalaVersion(scalaVersion)) {
        messages += s"This Scala version might not be supported in upcoming versions of Metals, " +
          s"please upgrade to Scala ${recommendedVersion(scalaVersion)}."
      } else if (!isLatestScalaVersion(scalaVersion)) {
        messages += s"Upgrade to Scala ${recommendedVersion(scalaVersion)} to enjoy the latest compiler improvements."
      }
      if (!scala.scalac.isSourcerootDeclared) {
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

  def deprecatedVersionWarning: Option[String] = {
    val deprecatedVersions = (for {
      target <- buildTargets.all.toIterator
      scala <- target.info.asScalaBuildTarget
      if ScalaVersions.isDeprecatedScalaVersion(scala.getScalaVersion())
    } yield scala.getScalaVersion()).toSet
    if (deprecatedVersions.isEmpty) {
      None
    } else {
      val recommendedVersions = deprecatedVersions.map(recommendedVersion)
      Some(
        messages.DeprecatedScalaVersion.message(
          deprecatedVersions,
          recommendedVersions
        )
      )
    }
  }

  private def problemSummary: Option[String] = {
    val targets = buildTargets.all.toList
    val isMissingSemanticdb = targets.filter(!_.isSemanticdbEnabled)
    val count = isMissingSemanticdb.length
    val isAllProjects = count == targets.size
    if (isMissingSemanticdb.isEmpty) {
      deprecatedVersionWarning
    } else if (isAllProjects) {
      Some(CheckDoctor.allProjectsMisconfigured)
    } else if (count == 1) {
      val name = isMissingSemanticdb.head.info.getDisplayName
      Some(CheckDoctor.singleMisconfiguredProject(name))
    } else {
      Some(CheckDoctor.multipleMisconfiguredProjects(count))
    }
  }

  private def buildTargetsHtml(): String = {
    new HtmlBuilder()
      .element("h1")(_.text("Metals Doctor"))
      .call(buildTargetsTable)
      .render
  }

  private def buildTargetsTable(html: HtmlBuilder): Unit = {
    html
      .element("p")(
        _.text(
          "These are the installed build targets for this workspace. " +
            "One build target corresponds to one classpath. For example, normally one sbt project maps to " +
            "two build targets: main and test."
        )
      )
    val targets = buildTargets.all.toList
    if (targets.isEmpty) {
      html
        .element("p")(
          _.text(
            s"${Icons.unicode.alert} No build targets were detected in this workspace so most functionality won't work."
          ).element("ul")(
            _.element("li")(
              _.text(
                s"Make sure the workspace directory '$workspace' matches the root of your build."
              )
            ).element("li")(
              _.text(
                "Try removing the directories .metals/ and .bloop/, then restart metals And import the build again."
              )
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
    targets.sortBy(_.info.getBaseDirectory).foreach { target =>
      val scala = target.info.asScalaBuildTarget
      val scalaVersion =
        scala.fold("<unknown>")(_.getScalaVersion)
      val definition: String =
        if (ScalaVersions.isSupportedScalaVersion(scalaVersion)) {
          Icons.unicode.check
        } else {
          Icons.unicode.alert
        }
      // NOTE(olafur) completions are currently the same as definition but in
      // the future we may want to update the definition column to take the
      // existence of library sources into account.
      val completions: String = definition
      val isSemanticdbNeeded = !target.isSemanticdbEnabled && target.isScalaTarget
      val references: String =
        if (isSemanticdbNeeded) {
          Icons.unicode.alert
        } else {
          Icons.unicode.check
        }
      val center = "style='text-align: center'"
      val recommenedFix =
        if (target.isScalaTarget)
          recommendation(scalaVersion, target.isSemanticdbEnabled, target)
        else ""
      html.element("tr")(
        _.element("td")(_.text(target.info.getDisplayName))
          .element("td")(_.text(scalaVersion))
          .element("td", center)(_.text(Icons.unicode.check))
          .element("td", center)(_.text(definition))
          .element("td", center)(_.text(completions))
          .element("td", center)(_.text(references))
          .element("td")(
            _.raw(recommenedFix)
          )
      )
    }
  }
}
