package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.Collections
import java.util.Optional

import scala.collection.mutable
import scala.util.Try

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixArguments

case class ScalafixProvider(
    buildTargets: BuildTargets,
    buffers: Buffers,
    workspace: AbsolutePath,
    embedded: Embedded,
    statusBar: StatusBar
) {
  import ScalafixProvider._
  private val scalafixCache = mutable.Map.empty[String, Scalafix]

  def organizeImports(file: AbsolutePath): List[l.TextEdit] = {
    val input = file.toInputFromBuffers(buffers)
    val scalafixConfPath = workspace.resolve(scalafixFileName)
    val scalafixConf: Optional[Path] =
      if (scalafixConfPath.isFile) Optional.of(scalafixConfPath.toNIO)
      else Optional.empty()
    val resOpt = for {
      (scalaVersion, scalaBinaryVersion, classPath) <-
        getScalaVersionAndClassPath(file)
      api <- getOrUpdateScalafixCache(scalaBinaryVersion)
      scalafixArgs = configureApi(api, scalaVersion, classPath)
      urlClassLoaderWithExternalRule = embedded.organizeImports(
        scalaBinaryVersion,
        api.getClass.getClassLoader
      )
    } yield {
      val scalacOption =
        if (scalaBinaryVersion == "2.13") "-Wunused:imports"
        else "-Ywarn-unused-import"
      scalafixArgs
        .withToolClasspath(urlClassLoaderWithExternalRule)
        .withConfig(scalafixConf)
        .withRules(List(organizeImportRuleName).asJava)
        .withPaths(List(file.toNIO).asJava)
        .withSourceroot(workspace.toNIO)
        .withScalacOptions(Collections.singletonList(scalacOption))
        .evaluate()
    }

    val fixedOpt = resOpt
      .flatMap(result => result.getFileEvaluations.headOption)
      .flatMap(_.previewPatches().asScala)
    fixedOpt.map(getTextEditsFrom(_, input)).getOrElse(Nil)
  }

  private def getTextEditsFrom(
      fixed: String,
      input: Input
  ): List[l.TextEdit] = {
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    if (fixed != input.text) {
      List(new l.TextEdit(fullDocumentRange, fixed))
    } else {
      Nil
    }
  }

  private def getScalaVersionAndClassPath(
      file: AbsolutePath
  ): Option[(ScalaVersion, ScalaBinaryVersion, util.List[Path])] = {
    for {
      identifier <- buildTargets.inverseSources(file)
      scalacOptions <- buildTargets.scalacOptions(identifier)
      scalaBuildTarget <- buildTargets.scalaInfo(identifier)
      scalaVersion = scalaBuildTarget.getScalaVersion
      semanticdbTarget = scalacOptions.targetroot(scalaVersion).toNIO
      scalaBinaryVersion = scalaBuildTarget.getScalaBinaryVersion
      classPath = scalacOptions.getClasspath.map(AbsolutePath(_).toNIO)
      _ = classPath.add(semanticdbTarget)
    } yield (scalaVersion, scalaBinaryVersion, classPath)
  }

  private def configureApi(
      api: Scalafix,
      scalaVersion: ScalaVersion,
      classPath: util.List[Path]
  ): ScalafixArguments = {
    api
      .newArguments()
      .withScalaVersion(scalaVersion)
      .withClasspath(classPath)
  }

  private def getOrUpdateScalafixCache(
      scalaBinaryVersion: ScalaBinaryVersion
  ): Option[Scalafix] = {
    scalafixCache
      .get(scalaBinaryVersion)
      .orElse(
        statusBar.trackBlockingTask("downloading scalafix") {
          Try(Scalafix.fetchAndClassloadInstance(scalaBinaryVersion)).toOption
            .map { api =>
              scalafixCache.update(scalaBinaryVersion, api)
              api
            }
        }
      )
  }
}

object ScalafixProvider {
  type ScalaBinaryVersion = String
  type ScalaVersion = String

  val organizeImportRuleName = "OrganizeImports"
  val scalafixFileName = ".scalafix.conf"

}
