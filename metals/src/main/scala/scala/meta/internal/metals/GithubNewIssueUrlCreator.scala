package scala.meta.internal.metals

import java.net.URLEncoder
import java.nio.charset.Charset

import scala.util.Properties

import scala.meta.internal.bsp.BspResolvedResult
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.RegenerateBspConfig
import scala.meta.internal.bsp.ResolvedBloop
import scala.meta.internal.bsp.ResolvedBspOne
import scala.meta.internal.bsp.ResolvedMultiple
import scala.meta.internal.bsp.ResolvedNone
import scala.meta.internal.builds.BuildTools

import org.eclipse.lsp4j.ClientInfo

class GithubNewIssueUrlCreator(
    getFoldersInfo: () => List[GitHubIssueFolderInfo],
    clientInfo: ClientInfo,
    charset: Charset,
) {

  def buildUrl(): String = {
    val foldersInfo = getFoldersInfo()
    val scalaVersions =
      getFoldersInfo()
        .flatMap(_.buildTargets.allScala)
        .map(_.scalaVersion)
        .toSet
        .mkString("; ")
    val clientVersion =
      Option(clientInfo.getVersion()).map(v => s" v$v").getOrElse("")
    val body =
      s"""|<!--
          |        Describe the bug ...
          |
          |        Reproduction steps
          |          1. Go to ...
          |          2. Click on ...
          |          3. Scroll down to ...
          |          4. See error
          |-->
          |
          |### Expected behaviour:
          |
          |<!-- A clear and concise description of what you expected to happen. -->
          |
          |**Operating system:**
          |${Properties.osName}
          |
          |**Java version:**
          |${Properties.javaVersion}
          |
          |**Editor/extension:**
          |${clientInfo.getName()}$clientVersion
          |
          |**Metals version:**
          |${BuildInfo.metalsVersion}
          |
          |### Extra context or search terms:
          |<!--
          |        - Any other context about the problem
          |        - Search terms to help others discover this
          |-->
          |
          |### Workspace information:
          | - **Scala versions:** $scalaVersions${selectedBuildTool(foldersInfo)}${selectedBuildServer(foldersInfo)}
          | - **All build tools in workspace:** ${foldersInfo.flatMap(_.buildTools.all).mkString("; ")}
          |""".stripMargin
    s"https://github.com/scalameta/metals/issues/new?body=${URLEncoder.encode(body, charset)}"
  }

  private def selectedBuildTool(
      foldersInfo: List[GitHubIssueFolderInfo]
  ): String = {
    val buildTools =
      foldersInfo.map(_.selectedBuildTool().getOrElse(""))

    if (buildTools.nonEmpty) {
      val value = buildTools.mkString("; ")
      s"""|
          | - **Build tools:** ${value}""".stripMargin
    } else ""
  }

  private def selectedBuildServer(
      foldersInfo: List[GitHubIssueFolderInfo]
  ): String = {
    val buildServers =
      foldersInfo.map { info =>
        import info._
        val buildServer = currentBuildServer()
          .map(s => s"${s.main.name} v${s.main.version}")
          .getOrElse {
            calculateNewBuildServer() match {
              case ResolvedBloop => "Disconnected: Bloop"
              case ResolvedBspOne(details) =>
                s"Disconnected: ${details.getName()}"
              case ResolvedMultiple(_, details) =>
                s"Disconnected: Multiple Found ${details.map(_.getName()).mkString("; ")}"
              case ResolvedNone => "Disconnected: None Found"
              case RegenerateBspConfig => "Regenerate bsp config"
            }
          }
        buildServer
      }
    s"""|
        | - **Build servers:** ${buildServers.mkString("; ")}""".stripMargin
  }
}

case class GitHubIssueFolderInfo(
    selectedBuildTool: () => Option[String],
    buildTargets: BuildTargets,
    currentBuildServer: () => Option[BspSession],
    calculateNewBuildServer: () => BspResolvedResult,
    buildTools: BuildTools,
)
