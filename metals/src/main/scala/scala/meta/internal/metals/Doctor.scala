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
    tables: Tables
)(implicit ec: ExecutionContext) {
  private val hasProblems = new AtomicBoolean(false)

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
  def check(): Unit = {
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
    if (!isSemanticdbEnabled) {
      if (isSupportedScalaVersion(scalaVersion)) {
        s"Run 'Build import' to enable code navigation."
      } else if (isSupportedScalaBinaryVersion(scalaVersion)) {
        s"Upgrade to Scala ${recommendedVersion(scalaVersion)} and " +
          s"run 'Build import' to enable code navigation."
      } else {
        s"Code navigation is not supported for this compiler version, upgrade to " +
          s"Scala ${BuildInfo.scala212} or ${BuildInfo.scala211} and " +
          s"run 'Build import' to enable code navigation."
      }
    } else {
      val messages = ListBuffer.empty[String]
      if (!isLatestScalaVersion(scalaVersion)) {
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

  private def problemSummary: Option[String] = {
    val targets = buildTargets.all.toList
    val isMissingSemanticdb = targets.filter(!_.isSemanticdbEnabled)
    val count = isMissingSemanticdb.length
    val isAllProjects = count == targets.size
    if (isMissingSemanticdb.isEmpty) {
      None
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
      .element("table")(
        _.element("thead")(
          _.element("tr")(
            _.element("th")(_.text("Build target"))
              .element("th")(_.text("Scala"))
              .element("th")(_.text("Diagnostics"))
              .element("th")(_.text("Goto definition"))
              .element("th")(_.text("Recommendation"))
          )
        ).element("tbody")(buildTargetRows)
      )
  }

  private def buildTargetRows(html: HtmlBuilder): Unit = {
    buildTargets.all.toList.sortBy(_.info.getBaseDirectory).foreach { target =>
      val scala = target.info.asScalaBuildTarget
      val scalaVersion =
        scala.fold("<unknown>")(_.getScalaVersion)
      val isSemanticdbEnabled = target.isSemanticdbEnabled
      val navigation: String =
        if (isSemanticdbEnabled) {
          Icons.unicode.check
        } else {
          Icons.unicode.alert
        }
      val center = "style='text-align: center'"
      html.element("tr")(
        _.element("td")(_.text(target.info.getDisplayName))
          .element("td")(_.text(scalaVersion))
          .element("td", center)(_.text(Icons.unicode.check))
          .element("td", center)(_.text(navigation))
          .element("td")(
            _.text(recommendation(scalaVersion, isSemanticdbEnabled, target))
          )
      )
    }
  }
}
