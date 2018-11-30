package scala.meta.internal.metals

import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.ExecuteCommandParams
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.Messages.CheckDoctor
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Helps the user figure out what is mis-configured in the build through the "Run doctor" command.
 *
 * At the moment, the doctor only validates that SemanticDB is enabled for all projects.
 */
final class Doctor(
    buildTargets: BuildTargets,
    config: MetalsServerConfig,
    languageClient: MetalsLanguageClient,
    httpServer: () => Option[MetalsHttpServer],
    tables: Tables
)(implicit ec: ExecutionContext) {

  /** Returns a full HTML page for the HTTP client. */
  def problemsHtmlPage: String = {
    new HtmlBuilder()
      .page("Metals Doctor") { html =>
        html.section("Build targets", buildTargetsTable)
      }
      .render
  }

  /** Executes the "Run doctor" server command. */
  def executeRunDoctor(): Unit = {
    if (config.executeClientCommand.isOn) {
      val html = buildTargetsHtml()
      val params = new ExecuteCommandParams(
        ClientCommands.RunDoctor.id,
        List(html: AnyRef).asJava
      )
      languageClient.metalsExecuteClientCommand(params)
    } else {
      httpServer() match {
        case Some(server) =>
          Urls.openBrowser(server.address + "/doctor")
        case None =>
          scribe.warn(
            "Unable to run doctor. To fix this problem enable -Dmetals.http=true"
          )
      }
    }
  }

  /** Checks if there are any potential problems and if any, notifies the user. */
  def check(): Unit = {
    problemSummary match {
      case Some(problem) =>
        val notification = tables.dismissedNotifications.DoctorWarning
        if (!notification.isDismissed) {
          notification.dismiss(2, TimeUnit.MINUTES)
          import scala.meta.internal.metals.Messages.CheckDoctor
          val params = CheckDoctor.params(problem)
          languageClient.showMessageRequest(params).asScala.foreach { item =>
            if (item == CheckDoctor.runDoctor) {
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
  private val isSupportedScalaVersion = BuildInfo.supportedScalaVersions.toSet
  private def isSupportedScalaBinaryVersion(scalaVersion: String): Boolean =
    Set("2.12", "2.11").exists { binaryVersion =>
      scalaVersion.startsWith(binaryVersion)
    }

  private val isLatestScalaVersion: Set[String] =
    Set(BuildInfo.scala212, BuildInfo.scala211)

  private def recommendedVersion(scalaVersion: String): String = {
    if (scalaVersion.startsWith("2.11")) BuildInfo.scala211
    else BuildInfo.scala212
  }

  private def recommendation(
      scalaVersion: String,
      isSemanticdbEnabled: Boolean
  ): String = {
    if (!isSemanticdbEnabled) {
      if (isSupportedScalaVersion(scalaVersion)) {
        s"Enable the SemanticDB compiler plugin."
      } else if (isSupportedScalaBinaryVersion(scalaVersion)) {
        s"Upgrade to Scala ${recommendedVersion(scalaVersion)}."
      } else {
        s"This compiler version is not supported. Consider upgrading to " +
          s"either Scala ${BuildInfo.scala212} or ${BuildInfo.scala211}."
      }
    } else if (!isLatestScalaVersion(scalaVersion)) {
      s"Upgrade to Scala ${recommendedVersion(scalaVersion)} to enjoy the latest compiler improvements."
    } else {
      ""
    }
  }

  private def problemSummary: Option[String] = {
    val isMissingSemanticdb = buildTargets.all.filter(!_.isSemanticdbEnabled)
    val count = isMissingSemanticdb.length
    val isAllProjects = count == buildTargets.all.size
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
    html.element("table")(
      _.element("thead")(
        _.element("tr")(
          _.element("td")(_.text("Build target"))
            .element("td")(_.text("Scala"))
            .element("td")(_.text("SemanticDB"))
            .element("td")(_.text("Recommendation"))
        )
      ).element("tbody")(buildTargetRows)
    )
  }

  private def buildTargetRows(html: HtmlBuilder): Unit = {
    buildTargets.all.sortBy(_.info.getBaseDirectory).foreach { target =>
      val scala = target.info.asScalaBuildTarget
      val scalaVersion =
        scala.fold("<unknown>")(_.getScalaVersion)
      val isSemanticdbEnabled = target.isSemanticdbEnabled
      val semanticdb: String =
        if (isSemanticdbEnabled) {
          s"${Icons.unicode.check}"
        } else {
          s"${Icons.unicode.alert} Not enabled."
        }
      html.element("tr")(
        _.element("td")(_.text(target.info.getDisplayName))
          .element("td")(_.text(scalaVersion))
          .element("td")(_.text(semanticdb))
          .element("td")(
            _.text(recommendation(scalaVersion, isSemanticdbEnabled))
          )
      )
    }
  }
}
