package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Random
import scala.util.Success
import scala.util.Try

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixEvaluation
import scalafix.interfaces.ScalafixFileEvaluationError

case class ScalafixProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
    embedded: Embedded,
    statusBar: StatusBar,
    compilations: Compilations,
    icons: Icons,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    buildClient: MetalsBuildClient,
    interactive: InteractiveSemanticdbs
)(implicit ec: ExecutionContext) {
  import ScalafixProvider._
  private val scalafixCache = TrieMap.empty[ScalaBinaryVersion, Scalafix]
  private val organizeImportRuleCache =
    TrieMap.empty[ScalaBinaryVersion, URLClassLoader]

  // Warms up the Scalafix instance so that the first organize imports request responds faster.
  def load(): Unit = {
    if (!Testing.isEnabled) {
      try {
        val targets =
          buildTargets.allScala.toList.groupBy(_.scalaVersion).flatMap {
            case (_, targets) => targets.headOption
          }
        val tmp = workspace
          .resolve(Directories.tmp)
          .resolve(s"Main${Random.nextLong()}.scala")
        val contents = "object Main{}\n"
        tmp.writeText(contents)
        for (target <- targets)
          scalafixEvaluate(tmp, target, contents, produceSemanticdb = true)

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
      scalaTarget: ScalaTarget,
      retried: Boolean = false
  ): Future[List[l.TextEdit]] = {
    val fromDisk = file.toInput
    val inBuffers = file.toInputFromBuffers(buffers)

    compilations.compilationFinished(file).flatMap { _ =>
      val scalafixEvaluation =
        scalafixEvaluate(
          file,
          scalaTarget,
          inBuffers.value,
          retried || isUnsaved(inBuffers.text, fromDisk.text)
        )

      scalafixEvaluation match {
        case Failure(exception) =>
          reportScalafixError(
            "Unable to run scalafix, please check logs for more info.",
            exception
          )
          Future.failed(exception)
        case Success(results)
            if !scalafixSucceded(results) && hasStaleSemanticdb(
              results
            ) && buildClient.buildHasErrors(file) =>
          val msg = "Attempt to organize your imports failed. " +
            "It looks like you have compilation issues causing your semanticdb to be stale. " +
            "Ensure everything is compiling and try again."
          scribe.warn(
            msg
          )
          languageClient.showMessage(
            MessageType.Warning,
            msg
          )
          Future.successful(Nil)
        case Success(results) if !scalafixSucceded(results) =>
          val scalafixError = getMessageErrorFromScalafix(results)
          val exception = ScalafixRunException(scalafixError)
          reportScalafixError(
            scalafixError,
            exception
          )
          if (!retried && hasStaleSemanticdb(results)) {
            // Retry, since the semanticdb might be stale
            organizeImports(file, scalaTarget, retried = true)
          } else {
            Future.failed(exception)
          }
        case Success(results) =>
          Future.successful {
            val edits = for {
              fileEvaluation <- results.getFileEvaluations().headOption
              patches <- fileEvaluation.previewPatches().asScala
            } yield textEditsFrom(patches, inBuffers)
            edits.getOrElse(Nil)
          }

      }
    }
  }

  private def createTemporarySemanticdb(
      file: AbsolutePath,
      contents: String
  ) = {
    interactive
      .textDocument(file, Some(contents))
      .documentIncludingStale
      .flatMap { semanticdb =>
        /* We remove all diagnostics if there is an error so that
         * we don't remove an import by mistake, which just has a typo
         * for example and would produce an unsued warning.
         * Without additional diagnotics imports will only get rearranged.
         */
        val toSave =
          if (semanticdb.diagnostics.exists(_.severity.isError))
            semanticdb.withDiagnostics(Seq.empty)
          else
            semanticdb
        val dir = workspace.resolve(Directories.tmp)
        file.toRelativeInside(workspace).flatMap { relativePath =>
          val writeTo =
            dir.resolve(SemanticdbClasspath.fromScalaOrJava(relativePath))
          writeTo.parent.createDirectories()
          val docs = TextDocuments(Seq(toSave))
          Files.write(writeTo.toNIO, docs.toByteArray)
          Option(dir.toNIO)
        }
      }

  }

  /**
   * Scalafix may be ran successfully, but that doesn't mean that every file
   * evaluation also ran succesfully. This ensure that the scalafix run was successful
   * and also that every file evaluation was successful.
   *
   * @param evaluation
   * @return true only if the evaulation for every single file contains no errors
   */
  private def scalafixSucceded(evaluation: ScalafixEvaluation): Boolean =
    evaluation.isSuccessful && evaluation
      .getFileEvaluations()
      .forall(_.isSuccessful)

  private def hasStaleSemanticdb(evaluation: ScalafixEvaluation): Boolean = {
    evaluation
      .getFileEvaluations()
      .headOption
      .flatMap(_.getError().asScala)
      .contains(ScalafixFileEvaluationError.StaleSemanticdbError)
  }

  /**
   * Assumes that [[ScalafixProvider.scalafixSucceded]] has been called and
   * returned false
   *
   * @param evaluation
   * @return the error message of the evaluation or file evaluation
   */
  private def getMessageErrorFromScalafix(
      evaluation: ScalafixEvaluation
  ): String = {
    (if (!evaluation.isSuccessful())
       evaluation.getErrorMessage().asScala
     else
       evaluation
         .getFileEvaluations()
         .headOption
         .flatMap(_.getErrorMessage().asScala))
      .getOrElse("Unexpected error while running Scalafix.")
  }

  private def scalafixConf: Option[Path] = {
    val defaultLocation = workspace.resolve(".scalafix.conf")
    userConfig().scalafixConfigPath match {
      case Some(path) if !path.isFile && defaultLocation.isFile =>
        languageClient.showMessage(
          MessageType.Warning,
          s"No configuration at $path, using default at $defaultLocation."
        )
        Some(defaultLocation.toNIO)
      case Some(path) if !path.isFile =>
        languageClient.showMessage(
          MessageType.Warning,
          s"No configuration at $path, using Scalafix defaults."
        )
        None
      case Some(path) => Some(path.toNIO)
      case None if defaultLocation.isFile =>
        Some(defaultLocation.toNIO)
      case _ => None
    }
  }

  /**
   * Tries to use the Scalafix rule to organize imports.
   *
   * @param file file to run the rule on
   * @param scalaTarget target with all the data about the module
   * @param inBuffers file version that might not be saved to disk
   * @param produceSemanticdb when set to true, we will try to create semanticdb and
   * save to disk for Scalafix to use. This make organize imports work even if the file is
   * unsaved. This however requires us to save both the file and semanticdb.
   * @return
   */
  private def scalafixEvaluate(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
      inBuffers: String,
      produceSemanticdb: Boolean
  ): Try[ScalafixEvaluation] = {
    val defaultScalaVersion = scalaTarget.scalaBinaryVersion
    val scalaBinaryVersion =
      if (defaultScalaVersion.startsWith("3")) "2.13" else defaultScalaVersion

    val targetRoot =
      if (produceSemanticdb) createTemporarySemanticdb(file, inBuffers)
      else
        buildTargets.scalacOptions(scalaTarget.info.getId()).map {
          scalacOptions =>
            scalacOptions.targetroot(scalaTarget.scalaVersion).toNIO
        }

    val sourceroot =
      if (produceSemanticdb)
        targetRoot.map(AbsolutePath(_)).getOrElse(workspace)
      else workspace

    val diskFilePath = if (produceSemanticdb) {
      file
        .toRelativeInside(workspace)
        .map { relativePath =>
          val tempFilePath = sourceroot.resolve(relativePath)
          tempFilePath.writeText(inBuffers)
          tempFilePath
        }
        .getOrElse(file)
    } else {
      file
    }

    val scalaVersion = scalaTarget.scalaVersion
    // It seems that Scalafix ignores the targetroot parameter and searches the classpath
    // Prepend targetroot to make sure that it's picked up first always
    val classpath =
      (targetRoot.toList ++ scalaTarget.fullClasspath).asJava

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

      val evaluated = api
        .newArguments()
        .withScalaVersion(scalaVersion)
        .withClasspath(classpath)
        .withToolClasspath(urlClassLoaderWithExternalRule)
        .withConfig(scalafixConf.asJava)
        .withRules(List(organizeImportRuleName).asJava)
        .withPaths(List(diskFilePath.toNIO).asJava)
        .withSourceroot(sourceroot.toNIO)
        .withScalacOptions(Collections.singletonList(scalacOption))
        .evaluate()

      if (produceSemanticdb)
        targetRoot.foreach(AbsolutePath(_).deleteRecursively())
      evaluated
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

  private def isUnsaved(fromBuffers: String, fromFile: String): Boolean = {
    // zipAll will extend the shorter collection, which is needed for accurate comparison
    fromBuffers.linesIterator
      .zipAll(fromFile.linesIterator, null, null)
      .exists { case (line1, line2) =>
        line1 != line2
      }
  }

}

object ScalafixProvider {
  type ScalaBinaryVersion = String
  type ScalaVersion = String

  case class ScalafixRunException(msg: String) extends Exception(msg)

  val organizeImportRuleName = "OrganizeImports"

}
