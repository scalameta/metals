package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
import java.util.{List => JList}

import scala.collection.concurrent.TrieMap
import scala.util.Try

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageType
import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixArguments

case class ScalafixProvider(
    buildTargets: BuildTargets,
    buffers: Buffers,
    scalafixConfigPath: RelativePath,
    workspace: AbsolutePath,
    embedded: Embedded,
    statusBar: StatusBar,
    icons: Icons,
    languageClient: MetalsLanguageClient
) {
  import ScalafixProvider._
  private val scalafixCache = TrieMap.empty[ScalaBinaryVersion, Scalafix]
  private val organizeImportRuleCache =
    TrieMap.empty[ScalaBinaryVersion, List[Path]]

  def organizeImports(file: AbsolutePath): List[l.TextEdit] = {
    val fileInput = file.toInput
    val unsavedFile = file.toInputFromBuffers(buffers)
    if (isUnsaved(unsavedFile.text, fileInput.text)) {
      scribe.info(s"Organize imports requires saving the file first")
      languageClient.showMessage(
        MessageType.Warning,
        s"Save ${file.toNIO.getFileName} before organizing imports"
      )
      Nil
    } else {
      if (Seq("scala", "sbt").contains(file.`extension`)) {
        val scalafixConfPath = workspace.resolve(scalafixConfigPath)
        val scalafixConf =
          if (scalafixConfPath.isFile) Some(scalafixConfPath.toNIO)
          else None
        val scalafixEvaluation = for {
          (scalaVersion, scalaBinaryVersion, classPath) <-
            scalaVersionAndClasspath(file)
          _ <-
            if (!ScalaVersions.isScala3Version(scalaVersion)) Some(())
            else {
              scribe.warn(
                s"Organize import doesn't work on $scalaVersion files"
              )
              None
            }
          api <- getOrUpdateScalafixCache(scalaBinaryVersion)
          scalafixArgs = configureApi(api, scalaVersion, classPath)
          urlClassLoaderWithExternalRule <- getOrUpdateRuleCache(
            scalaBinaryVersion,
            api.getClass.getClassLoader
          )
        } yield {
          val scalacOption =
            if (scalaBinaryVersion == "2.13") "-Wunused:imports"
            else "-Ywarn-unused-import"

          scalafixArgs
            .withToolClasspath(urlClassLoaderWithExternalRule)
            .withConfig(scalafixConf.asJava)
            .withRules(List(organizeImportRuleName).asJava)
            .withPaths(List(file.toNIO).asJava)
            .withSourceroot(workspace.toNIO)
            .withScalacOptions(Collections.singletonList(scalacOption))
            .evaluate()
        }

        val newFileContentOpt = scalafixEvaluation
          .flatMap(result => result.getFileEvaluations.headOption)
          .flatMap(_.previewPatches().asScala)
        newFileContentOpt.map(textEditsFrom(_, fileInput)).getOrElse(Nil)
      } else {
        scribe.info(
          s"""|Could not organize import for ${file.toNIO.getFileName}. Should end with .scala or .sbt"""
        )
        Nil
      }
    }
  }

  private def textEditsFrom(
      newFileContent: String,
      input: Input
  ): List[l.TextEdit] = {
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    if (newFileContent != input.text) {
      List(new l.TextEdit(fullDocumentRange, newFileContent))
    } else {
      Nil
    }
  }

  private def scalaVersionAndClasspath(
      file: AbsolutePath
  ): Option[(ScalaVersion, ScalaBinaryVersion, JList[Path])] =
    for {
      identifier <- buildTargets.inverseSources(file)
      scalacOptions <- buildTargets.scalacOptions(identifier)
      scalaBuildTarget <- buildTargets.scalaInfo(identifier)
      scalaVersion = scalaBuildTarget.getScalaVersion
      semanticdbTarget = scalacOptions.targetroot(scalaVersion).toNIO
      scalaBinaryVersion = scalaBuildTarget.getScalaBinaryVersion
      classPath = scalacOptions.getClasspath.map(_.toAbsolutePath.toNIO)
      _ = classPath.add(semanticdbTarget)
    } yield (scalaVersion, scalaBinaryVersion, classPath)

  private def configureApi(
      api: Scalafix,
      scalaVersion: ScalaVersion,
      classPath: JList[Path]
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
        statusBar.trackBlockingTask("Downloading scalafix") {
          Try(Scalafix.fetchAndClassloadInstance(scalaBinaryVersion)).toOption
            .map { api =>
              scalafixCache.update(scalaBinaryVersion, api)
              api
            }
        }
      )
  }

  private def getOrUpdateRuleCache(
      scalaBinaryVersion: ScalaBinaryVersion,
      scalafixClassLoader: ClassLoader
  ): Option[URLClassLoader] = {
    val pathsOpt = organizeImportRuleCache
      .get(scalaBinaryVersion)
      .orElse(
        statusBar.trackBlockingTask("Downloading organize import rule") {
          Try(Embedded.organizeImportRule(scalaBinaryVersion)).toOption
            .map { paths =>
              organizeImportRuleCache.update(scalaBinaryVersion, paths)
              paths
            }
        }
      )
    pathsOpt.map(paths =>
      Embedded.toClassLoader(
        Classpath(paths.map(AbsolutePath(_))),
        scalafixClassLoader
      )
    )
  }

  private def isUnsaved(fromBuffers: String, fromFile: String): Boolean =
    fromBuffers.linesIterator.zip(fromFile.linesIterator).exists {
      case (line1, line2) => line1 != line2
    }

}

object ScalafixProvider {
  type ScalaBinaryVersion = String
  type ScalaVersion = String

  val organizeImportRuleName = "OrganizeImports"
}
