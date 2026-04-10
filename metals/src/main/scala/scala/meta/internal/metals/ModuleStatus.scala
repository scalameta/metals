package scala.meta.internal.metals

import scala.concurrent.Promise

import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class ModuleStatus(
    client: MetalsLanguageClient,
    focusedPath: () => Option[AbsolutePath],
    serviceForPath: AbsolutePath => ModulesService,
    icons: Icons,
) {

  def onFinishCompileBuildTarget(
      compiled: BuildTargetIdentifier
  ): Option[Unit] = {
    for {
      path <- focusedPath()
      handler = serviceForPath(path)
      buildTarget <- handler.buildTargets.inverseSources(path)
      info <- handler.buildTargets.info(buildTarget)
      if compiled == buildTarget || info.getDependencies().contains(compiled)
    } yield refresh()
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
            handler.modulesDiagnostics
              .upstreamTargetsWithCompilationErrors(buildTarget.id)
              .flatMap(handler.buildTargets.jvmTarget)
              .headOption match {
              case Some(buildTargetWithError) =>
                client.metalsStatus(
                  ModuleStatus.upstreamCompilationIssues(
                    buildTarget.displayName,
                    buildTargetWithError.displayName,
                    icons,
                  )
                )
              case None =>
                client.metalsStatus(
                  ModuleStatus.ok(buildTarget.displayName, icons)
                )
            }
        }
    }
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

  def upstreamCompilationIssues(
      buildTargetName: String,
      buildTargetWithErrorName: String,
      icons: Icons,
  ): MetalsStatusParams =
    MetalsStatusParams(
      s"$buildTargetName ($buildTargetWithErrorName ${icons.error})",
      "error",
      show = true,
      tooltip =
        s"Upstream module `$buildTargetWithErrorName` has compiler errors.",
    ).withStatusType(StatusType.module)

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

trait ModulesService {
  def buildTargets: BuildTargets
  def folder: AbsolutePath
  def buildServerPromise: Promise[Unit]
  def modulesDiagnostics: Diagnostics
}
