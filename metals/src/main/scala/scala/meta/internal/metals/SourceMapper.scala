package scala.meta.internal.metals

import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final case class SourceMapper(
    buildTargets: BuildTargets,
    buffers: Buffers,
) {
  def mappedFrom(path: AbsolutePath): Option[AbsolutePath] =
    buildTargets.mappedFrom(path)

  def mappedTo(path: AbsolutePath): Option[AbsolutePath] =
    buildTargets.mappedTo(path).map(_.path)
  def mappedLineForServer(path: AbsolutePath, line: Int): Int =
    buildTargets.mappedLineForServer(path, line).getOrElse(line)
  def mappedLineForClient(path: AbsolutePath, line: Int): Int =
    buildTargets.mappedLineForClient(path, line).getOrElse(line)

  def pcMapping(
      path: AbsolutePath,
      scalaVersion: String,
  ): (Input.VirtualFile, l.Position => l.Position, AdjustLspData) = {

    def input = path.toInputFromBuffers(buffers)
    def default = {
      val viaBuildTargets =
        buildTargets.mappedTo(path).map(_.update(input.value))
      viaBuildTargets.getOrElse(
        (input, identity[l.Position] _, AdjustedLspData.default)
      )
    }

    val forScripts =
      if (path.isSbt) {
        buildTargets
          .sbtAutoImports(path)
          .map(
            SbtBuildTool.sbtInputPosAdjustment(input, _)
          )
      } else if (
        path.isWorksheet && ScalaVersions.isScala3Version(scalaVersion)
      ) {
        WorksheetProvider.worksheetScala3Adjustments(input)
      } else if (path.isTwirlTemplate) {
        val playVersion = buildTargets
          .inverseSources(path)
          .flatMap { targetId =>
            buildTargets
              .targetData(targetId)
              .flatMap { data =>
                data.buildTargetDependencyModules
                  .getOrElse(targetId, Nil)
                  .find { m =>
                    val isPlayOrg =
                      m.getOrganization() == "org.playframework" ||
                        m.getOrganization() == "com.typesafe.play"
                    isPlayOrg && m.getName().startsWith("play_")
                  }
                  .map(_.getVersion())
              }
              .orElse {
                buildTargets
                  .targetJarClasspath(targetId)
                  .getOrElse(Nil)
                  .collectFirst {
                    case jar if jar.filename.startsWith("play_") =>
                      val noExt = jar.filename.stripSuffix(".jar")
                      val dashIdx = noExt.indexOf('-')
                      if (dashIdx >= 0) noExt.substring(dashIdx + 1)
                      else "3.0"
                  }
              }
          }
        Try(TwirlAdjustments(input, scalaVersion, playVersion)).toOption
      } else None

    forScripts.getOrElse(default)
  }
}
