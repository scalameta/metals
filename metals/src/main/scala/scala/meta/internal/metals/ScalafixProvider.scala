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

case class ScalafixProvider(
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

  def organizeImports(
      file: AbsolutePath,
      scalaVersion: ScalaVersion,
      scalaBinaryVersion: ScalaBinaryVersion,
      classpath: JList[Path]
  ): List[l.TextEdit] = {
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
      val scalafixConfPath = workspace.resolve(scalafixConfigPath)
      val scalafixConf =
        if (scalafixConfPath.isFile) Some(scalafixConfPath.toNIO)
        else None
      val scalafixEvaluation = for {
        api <- getScalafix(scalaBinaryVersion)
        urlClassLoaderWithExternalRule <- getRuleClassLoader(
          scalaBinaryVersion,
          api.getClass.getClassLoader
        )
      } yield {
        val scalacOption =
          if (scalaBinaryVersion == "2.13") "-Wunused:imports"
          else "-Ywarn-unused-import"

        api
          .newArguments()
          .withScalaVersion(scalaVersion)
          .withClasspath(classpath)
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

  private def getScalafix(
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

  private def getRuleClassLoader(
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

  def scalaVersionAndClasspath(
      file: AbsolutePath,
      buildTargets: BuildTargets
  ): Option[(ScalaVersion, ScalaBinaryVersion, JList[Path])] =
    for {
      buildId <- buildTargets.inverseSources(file)
      scalacOptions <- buildTargets.scalacOptions(buildId)
      scalaBuildTarget <- buildTargets.scalaInfo(buildId)
      scalaVersion = scalaBuildTarget.getScalaVersion
      semanticdbTarget = scalacOptions.targetroot(scalaVersion).toNIO
      scalaBinaryVersion = scalaBuildTarget.getScalaBinaryVersion
      classPath = scalacOptions.getClasspath.map(_.toAbsolutePath.toNIO)
      _ = classPath.add(semanticdbTarget)
    } yield (scalaVersion, scalaBinaryVersion, classPath)
}
