package scala.meta.internal.metals

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Promise

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.internal.tvp.MetalsCommand
import scala.meta.io.AbsolutePath
import scala.meta.pc.reports.Report

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

trait ReportTracker {
  def reportCreated(report: Report): Unit
}

class ModuleStatus(
    client: MetalsLanguageClient,
    focusedPath: () => Option[AbsolutePath],
    serviceForPath: AbsolutePath => {
      def buildTargets: BuildTargets
      def folder: AbsolutePath
      def buildServerPromise: Promise[Unit]
    },
    icons: Icons,
) extends ReportTracker {

  private val reports =
    new ConcurrentHashMap[BuildTargetIdentifier, List[Report]]()

  override def reportCreated(report: Report): Unit = {
    for {
      path <- report.path.asScala
      absPath = path.toAbsolutePath
      buildTarget <- serviceForPath(absPath).buildTargets.inverseSources(
        absPath
      )
    } yield {
      reports.compute(
        buildTarget,
        (_, oldReports) =>
          oldReports match {
            case null => List(report)
            case _ => report :: oldReports
          },
      )
      if (focusedPath().contains(absPath)) refresh()
    }
  }

  def refresh(): Unit = {
    focusedPath() match {
      case None => client.metalsStatus(ModuleStatus.clear())
      case Some(path) =>
        val handler = serviceForPath(path)
        val inferredBuildTarget = for {
          buildTargetId <- handler.buildTargets.inverseSources(path)
          buildTarget <- handler.buildTargets.jvmTarget(buildTargetId)
        } yield buildTarget

        inferredBuildTarget match {
          case None if !handler.buildServerPromise.isCompleted =>
            client.metalsStatus(ModuleStatus.importing())
          case None =>
            client.metalsStatus(ModuleStatus.noBuildTarget(icons))
          case Some(buildTarget) =>
            reports.getOrDefault(buildTarget.id, Nil) match {
              case Nil =>
                client.metalsStatus(
                  ModuleStatus.ok(buildTarget.displayName, icons)
                )
              case errorReports =>
                client.metalsStatus(
                  ModuleStatus.warnings(
                    buildTarget.displayName,
                    buildTarget.id,
                    errorReports.size,
                    icons,
                  )
                )
            }
        }
    }
  }

  def clearReports(id: BuildTargetIdentifier): List[Report] = {
    reports.remove(id)
  }

}

object ModuleStatus {
  def clear(): MetalsStatusParams =
    MetalsStatusParams(
      "",
      "info",
      show = false,
    ).withStatusType(StatusType.module)

  def importing(): MetalsStatusParams =
    MetalsStatusParams(
      s"importing...",
      "info",
      show = true,
    ).withStatusType(StatusType.module)

  def ok(buildTargetName: String, icons: Icons): MetalsStatusParams =
    MetalsStatusParams(
      s"$buildTargetName ${icons.check}",
      "info",
      show = true,
      tooltip = s"No errors for the build target.",
      command = ServerCommands.RunDoctor.id,
      commandTooltip = "Open doctor.",
    ).withStatusType(StatusType.module)

  def warnings(
      buildTargetName: String,
      id: BuildTargetIdentifier,
      errorsNumber: Int,
      icons: Icons,
  ): MetalsStatusParams =
    MetalsStatusParams(
      s"$buildTargetName ($errorsNumber) ${icons.alert}",
      "warn",
      show = true,
      tooltip = s"$errorsNumber new error reports for the file.",
      metalsCommand = MetalsCommand(
        s"Show error reports for $buildTargetName",
        ServerCommands.ShowReportsForBuildTarget.id,
        s"Show error reports for $buildTargetName",
        Array(id.getUri()),
      ),
      commandTooltip = "Show error reports.",
    ).withStatusType(StatusType.module)

  def bspErrorParams(
      icons: Icons,
      buildTargetName: String,
      errorMessage: String,
      logLine: Option[Int],
      workspace: AbsolutePath,
  ): MetalsStatusParams = {
    val (command, metalsCommand, commandTooltip) =
      logLine match {
        case Some(logLine) =>
          (
            null,
            MetalsCommand(
              "Go to issue in logs",
              ClientCommands.GotoLocation.id,
              "Go to issue in logs",
              Array(
                ClientCommands.WindowLocation(
                  workspace.resolve(Directories.log).toURI.toString(),
                  new Range(new Position(logLine, 0), new Position(logLine, 0)),
                  otherWindow = true,
                )
              ).asInstanceOf[Array[AnyRef]],
            ),
            "Go to issue in logs",
          )
        case _ => (ServerCommands.RunDoctor.id, null, "Open doctor.")
      }
    MetalsStatusParams(
      s"$buildTargetName ${icons.error}",
      "error",
      show = true,
      tooltip = errorMessage,
      command = command,
      metalsCommand = metalsCommand,
      commandTooltip = commandTooltip,
    ).withStatusType(StatusType.module)
  }

  def noBuildTarget(
      icons: Icons
  ): MetalsStatusParams =
    MetalsStatusParams(
      s"no target ${icons.error}",
      "error",
      show = true,
      tooltip = "No build target for file found.",
      command = ServerCommands.RunDoctor.id,
      commandTooltip = "Open doctor.",
    ).withStatusType(StatusType.module)
}
