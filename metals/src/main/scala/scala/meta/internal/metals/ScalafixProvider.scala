package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixEvaluation

case class ScalafixProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
    embedded: Embedded,
    statusBar: StatusBar,
    compilations: Compilations,
    icons: Icons,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets
)(implicit ec: ExecutionContext) {
  import ScalafixProvider._
  private val scalafixCache = TrieMap.empty[ScalaBinaryVersion, Scalafix]
  private val organizeImportRuleCache =
    TrieMap.empty[ScalaBinaryVersion, URLClassLoader]

  // Warms up the Scalafix instance so that the first organize imports request responds faster.
  def load(): Unit = {
    if (!Testing.isEnabled) {
      try {
        val targets = buildTargets.all.toList.groupBy(_.scalaVersion).flatMap {
          case (_, targets) => targets.headOption
        }
        val tmp = AbsolutePath(Files.createTempFile("metals", ".scala"))
        tmp.writeText("object Main{}\n")
        for (target <- targets)
          scalafixEvaluate(
            tmp,
            target.scalaVersion,
            target.scalaBinaryVersion,
            target.fullClasspath
          )
        tmp.delete()
      } catch {
        case e: Throwable =>
          scribe.debug(
            s"Scalafix issue while warming up due to issue: ${e.getMessage()}"
          )
      }
    }
  }

  def organizeImports(
      file: AbsolutePath,
      scalaTarget: ScalaTarget
  ): Future[List[l.TextEdit]] = {
    val fromDisk = file.toInput
    val inBuffers = file.toInputFromBuffers(buffers)
    if (isUnsaved(inBuffers.text, fromDisk.text)) {
      scribe.info(s"Organize imports requires saving the file first")
      languageClient.showMessage(
        MessageType.Warning,
        s"Save ${file.toNIO.getFileName} to compile it before organizing imports"
      )
      Future.successful(Nil)
    } else {
      compilations.compilationFinished(file).flatMap { _ =>
        val scalaBinaryVersion = scalaTarget.scalaBinaryVersion
        val scalafixEvaluation = scalafixEvaluate(
          file,
          scalaTarget.scalaVersion,
          scalaBinaryVersion,
          scalaTarget.fullClasspath
        )

        scalafixEvaluation match {
          case Failure(exception) =>
            reportScalafixError(
              "Unable to run scalafix, please check logs for more info.",
              exception
            )
            Future.failed(exception)
          case Success(results) if !scalafixSucceded(results) =>
            val scalafixError = getMessageErrorFromScalafix(results)
            val exception = ScalafixRunException(scalafixError)
            reportScalafixError(
              scalafixError,
              exception
            )
            Future.failed(exception)
          case Success(results) =>
            Future.successful {
              val edits = for {
                fileEvaluation <- results.getFileEvaluations().headOption
                patches <- fileEvaluation.previewPatches().asScala
              } yield textEditsFrom(patches, fromDisk)
              edits.getOrElse(Nil)
            }
        }
      }
    }
  }
  private def scalafixSucceded(evaluation: ScalafixEvaluation): Boolean =
    evaluation.isSuccessful && evaluation
      .getFileEvaluations()
      .forall(_.isSuccessful)

  private def getMessageErrorFromScalafix(
      evaluation: ScalafixEvaluation
  ): String = {
    (if (!evaluation.isSuccessful)
       evaluation.getErrorMessage().asScala
     else
       evaluation
         .getFileEvaluations()
         .headOption
         .flatMap(_.getErrorMessage.asScala)).getOrElse(defaultErrorMessage)
  }

  private def scalafixConf: Option[Path] = {
    val scalafixConfPath = userConfig().scalafixConfigPath
      .getOrElse(workspace.resolve(".scalafix.conf"))
    if (scalafixConfPath.isFile) Some(scalafixConfPath.toNIO)
    else None
  }

  private def scalafixEvaluate(
      file: AbsolutePath,
      scalaVersion: String,
      scalaBinaryVersion: String,
      fullClasspath: java.util.List[Path]
  ): Try[ScalafixEvaluation] = {
    for {
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
        .withClasspath(fullClasspath)
        .withToolClasspath(urlClassLoaderWithExternalRule)
        .withConfig(scalafixConf.asJava)
        .withRules(List(organizeImportRuleName).asJava)
        .withPaths(List(file.toNIO).asJava)
        .withSourceroot(workspace.toNIO)
        .withScalacOptions(Collections.singletonList(scalacOption))
        .evaluate()
    }
  }

  private def reportScalafixError(
      message: String,
      exception: Throwable
  ): Unit = {
    val params = new MessageParams(MessageType.Error, message)
    scribe.error(message, exception)
    languageClient.showMessage(params)
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
  ): Try[Scalafix] = {
    scalafixCache.get(scalaBinaryVersion) match {
      case Some(value) => Success(value)
      case None =>
        statusBar.trackBlockingTask("Downloading scalafix") {
          val scalafix =
            Try(Scalafix.fetchAndClassloadInstance(scalaBinaryVersion))
          scalafix.foreach(api => scalafixCache.update(scalaBinaryVersion, api))
          scalafix
        }
    }

  }

  private def getRuleClassLoader(
      scalaBinaryVersion: ScalaBinaryVersion,
      scalafixClassLoader: ClassLoader
  ): Try[URLClassLoader] = {
    organizeImportRuleCache.get(scalaBinaryVersion) match {
      case Some(value) => Success(value)
      case None =>
        statusBar.trackBlockingTask("Downloading organize import rule") {
          val organizeImportRule =
            Try(Embedded.organizeImportRule(scalaBinaryVersion)).map { paths =>
              val classloader = Embedded.toClassLoader(
                Classpath(paths.map(AbsolutePath(_))),
                scalafixClassLoader
              )
              organizeImportRuleCache.update(scalaBinaryVersion, classloader)
              classloader
            }
          organizeImportRule
        }
    }
  }

  private def isUnsaved(fromBuffers: String, fromFile: String): Boolean =
    fromBuffers.linesIterator.zip(fromFile.linesIterator).exists {
      case (line1, line2) => line1 != line2
    }

}

object ScalafixProvider {
  type ScalaBinaryVersion = String
  type ScalaVersion = String

  case class ScalafixRunException(msg: String) extends Exception(msg)

  val defaultErrorMessage: String =
    """|Unexpected error while running scalafix. Semanticdb might have been stale, which 
       |would require successful compilation to be created.""".stripMargin
  val organizeImportRuleName = "OrganizeImports"

}
