package scala.meta.internal.worksheets

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta._
import scala.meta.inputs.Input.VirtualFile
import scala.meta.internal.metals.AdjustLspData
import scala.meta.internal.metals.AdjustedLspData
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsSlowTaskParams
import scala.meta.internal.mtags.MD5
import scala.meta.internal.pc.CompilerJobQueue
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.worksheets.MdocEnrichments._
import scala.meta.internal.worksheets.WorksheetProvider.MdocKey
import scala.meta.internal.worksheets.WorksheetProvider.MdocRef
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.error.SimpleResolutionError
import mdoc.interfaces.EvaluatedWorksheet
import mdoc.interfaces.Mdoc
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position

/**
 * Implements interactive worksheets for "*.worksheet.sc" file extensions.
 *
 * Code is evaluated on file save using mdoc: https://scalameta.org/mdoc/
 */
class WorksheetProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    userConfig: () => UserConfiguration,
    statusBar: StatusBar,
    diagnostics: Diagnostics,
    embedded: Embedded,
    publisher: WorksheetPublisher,
    compilers: Compilers,
    compilations: Compilations,
    scalaVersionSelector: ScalaVersionSelector,
)(implicit ec: ExecutionContext)
    extends Cancelable {

  // Worksheet evaluation happens on a single threaded job queue. Jobs are
  // prioritized using the same order as completion/hover requests:
  // first-come last-out.
  private val jobs = CompilerJobQueue()
  // Executor for stopping threads. We don't reuse the scheduled executor from
  // MetalsLanguageServer because this exector service may occasionally block
  // and we don't want to block on other features like the status bar.
  private lazy val threadStopper: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  private val cancelables = new MutableCancelable()
  private val mdocs = new TrieMap[MdocKey, MdocRef]()
  private val worksheetsDigests = new TrieMap[AbsolutePath, String]()
  private val exportableEvaluations =
    new TrieMap[VirtualFile, EvaluatedWorksheet]()

  private def fallabackMdoc: Mdoc = {
    val scalaVersion =
      scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
    mdocs
      .get(MdocKey.Default)
      .flatMap(ref =>
        if (ref.scalaVersion == scalaVersion) Some(ref.value)
        else {
          ref.value.shutdown()
          None
        }
      )
      .getOrElse {
        val mdoc =
          embedded
            .mdoc(scalaVersion)
            .withClasspath(Embedded.scalaLibrary(scalaVersion).asJava)
        val ref = MdocRef(scalaVersion, mdoc)
        mdocs.update(MdocKey.Default, ref)
        ref.value
      }
  }

  def onBuildTargetDidCompile(target: BuildTargetIdentifier): Unit = {
    clearBuildTarget(MdocKey.BuildTarget(target))
  }

  private def clearBuildTarget(key: MdocKey): Unit = {
    mdocs.remove(key).foreach(_.value.shutdown())
  }

  def reset(): Unit = {
    mdocs.keysIterator.foreach(clearBuildTarget)
  }

  def cancel(): Unit = {
    cancelables.cancel()
    jobs.shutdown()
    threadStopper.shutdown()
    reset()
  }

  def onDidFocus(path: AbsolutePath): Future[Unit] = Future {
    if (path.isWorksheet) {
      val input = path.toInputFromBuffers(buffers)
      exportableEvaluations.get(input) match {
        case Some(evaluatedWorksheet) =>
          publisher.publish(languageClient, path, evaluatedWorksheet)
        case None =>
      }
    }
  }

  def evaluateAndPublish(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[Unit] = {
    val possibleBuildTarget = buildTargets.inverseSources(path)
    val previouslyCompiled = compilations.previouslyCompiled.toSeq

    (possibleBuildTarget match {
      case Some(bdi)
          if (previouslyCompiled.isEmpty || !previouslyCompiled.contains(
            bdi
          )) =>
        compilations.compileTarget(bdi).ignoreValue
      case _ =>
        Future.successful(())
    }).flatMap(_ =>
      evaluateAsync(path, token).map(
        _.foreach(publisher.publish(languageClient, path, _))
      )
    )
  }

  /**
   * Fallback hover for results.
   * While for the actual code hover's provided by Compilers,
   * for evaluated results hover's provided here
   */
  def hover(path: AbsolutePath, position: Position): Option[Hover] = {
    publisher.hover(path, position)
  }

  /**
   * Check to see for a given path if there is an evaluated worksheet that
   * matches thie exact input.
   *
   * @param path to the input used to search previous evaluations.
   * @return possible evaluated output.
   */
  def copyWorksheetOutput(path: AbsolutePath): Option[String] = {
    val input = path.toInputFromBuffers(buffers)
    exportableEvaluations.get(input) match {
      case None => None
      case Some(evalutatedWorksheet) =>
        val toExport = new StringBuilder
        val originalLines = input.value.split("\n").toBuffer.zipWithIndex

        originalLines.foreach { case (lineContent, lineNumber) =>
          val lineHasOutput = evalutatedWorksheet
            .statements()
            .asScala
            .find(_.position.endLine() == lineNumber)

          lineHasOutput match {
            case Some(statement) =>
              val detailLines = statement
                .details()
                .split("\n")
                .map { line =>
                  // println in worksheets already come with //
                  if (line.trim().startsWith("//")) line
                  else s"// ${line}"
                }
                .mkString("\n")

              toExport.append(s"\n${lineContent}\n${detailLines}")
            case None => toExport.append(s"\n${lineContent}")
          }
        }

        Some(toExport.result())
    }
  }

  private def evaluateAsync(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[Option[EvaluatedWorksheet]] = {
    val result = new CompletableFuture[Option[EvaluatedWorksheet]]()
    def completeEmptyResult() = result.complete(None)
    token.onCancel().asScala.foreach { isCancelled =>
      if (isCancelled) {
        completeEmptyResult()
      }
    }
    val onError: PartialFunction[Throwable, Option[EvaluatedWorksheet]] = {
      case InterruptException() =>
        None
      case e: SimpleResolutionError =>
        scribe.error(e.getMessage())
        languageClient.showMessage(
          Messages.errorMessageParams(e.getMessage().takeWhile(_ != '\n'))
        )
        None
      case e: Throwable =>
        // NOTE(olafur): we catch all exceptions because of https://github.com/scalameta/metals/issues/1456
        scribe.error(s"worksheet: $path", e)
        None
    }
    def runEvaluation(): Unit = {
      cancelables.cancel() // Cancel previous worksheet evaluations.
      val timer = new Timer(Time.system)
      result.asScala.foreach { _ =>
        scribe.info(
          s"time: evaluated worksheet '${path.filename}' in $timer"
        )
      }
      cancelables.add(Cancelable(() => completeEmptyResult()))
      statusBar.trackFuture(
        s"Evaluating ${path.filename}",
        result.asScala,
        showTimer = true,
      )
      token.checkCanceled()
      // NOTE(olafurpg) Run evaluation in a custom thread so that we can
      // `Thread.stop()` it in case of infinite loop. I'm not aware of any
      // other JVM APIs that allow killing a runnable even in the face of
      // infinite loops.
      val thread = new Thread(s"Evaluating Worksheet ${path.filename}") {
        override def run(): Unit = {
          result.complete(
            try Some(evaluateWorksheet(path))
            catch onError
          )
        }
      }
      interruptThreadOnCancel(path, result, thread)
      thread.start()
      thread.join()
    }
    jobs.submit(
      result,
      () => {
        try runEvaluation()
        catch {
          case e: Throwable =>
            onError(e)
            ()
        }
      },
    )
    result.asScala.recover(onError)
  }

  /**
   * Prompts the user to cancel the task after a few seconds.
   *
   * Attempts to gracefully shut down the thread when users requests to cancel:
   * First tries `Thread.interrupt()` with fallback to `Thread.stop()` after
   * one second if interruption doesn't work.
   */
  private def interruptThreadOnCancel(
      path: AbsolutePath,
      result: CompletableFuture[Option[EvaluatedWorksheet]],
      thread: Thread,
  ): Unit = {
    // Last resort, if everything else fails we use `Thread.stop()`.
    val stopThread = new Runnable {
      def run(): Unit = {
        if (thread.isAlive()) {
          scribe.warn(s"thread stop: ${thread.getName()}")
          thread.stop()
        }
      }
    }
    // If the program is running for more than
    // `userConfig().worksheetCancelTimeout`, then display a prompt for the user
    // to cancel the program.
    val interruptThread = new Runnable {
      def run(): Unit = {
        if (!result.isDone()) {
          val cancel = languageClient.metalsSlowTask(
            new MetalsSlowTaskParams(
              s"Evaluating worksheet '${path.filename}'",
              quietLogs = true,
              secondsElapsed = userConfig().worksheetCancelTimeout,
            )
          )
          cancel.asScala.foreach { c =>
            if (c.cancel && thread.isAlive()) {
              // User has requested to cancel a running program. first line of
              // defense is `Thread.interrupt()`. Fingers crossed it's enough.
              result.complete(None)
              threadStopper.schedule(stopThread, 3, TimeUnit.SECONDS)
              scribe.warn(s"thread interrupt: ${thread.getName()}")
              thread.interrupt()
            }
          }
          result.asScala.onComplete(_ => cancel.cancel(true))
        }
      }
    }
    threadStopper.schedule(
      interruptThread,
      userConfig().worksheetCancelTimeout,
      TimeUnit.SECONDS,
    )
  }

  private def fetchDependencySources(
      dependencies: Seq[Dependency]
  ): List[Path] = {
    Fetch
      .create()
      .withDependencies(dependencies: _*)
      .addClassifiers("sources")
      .fetchResult()
      .getFiles()
      .map(_.toPath())
      .asScala
      .toList
  }

  private def evaluateWorksheet(
      path: AbsolutePath
  ): EvaluatedWorksheet = {
    val mdoc = getMdoc(path)
    val input = path.toInputFromBuffers(buffers)
    val relativePath = path.toRelative(workspace)
    val evaluatedWorksheet =
      mdoc.evaluateWorksheet(relativePath.toString(), input.value)
    val classpath = evaluatedWorksheet.classpath().asScala.toList
    val previousDigest = worksheetsDigests.getOrElse(path, "")
    val newDigest = calculateDigest(classpath)

    exportableEvaluations.update(
      input,
      evaluatedWorksheet,
    )

    if (newDigest != previousDigest) {
      worksheetsDigests.put(path, newDigest)
      val sourceDeps = fetchDependencySources(
        evaluatedWorksheet.dependencies().asScala.toSeq
      )
      compilers.restartWorksheetPresentationCompiler(
        path,
        classpath,
        sourceDeps.filter(_.toString().endsWith("-sources.jar")),
      )
    }

    val toPublish = evaluatedWorksheet
      .diagnostics()
      .iterator()
      .asScala
      .map(_.toLsp)
      .toSeq
    diagnostics.onPublishDiagnostics(
      path,
      toPublish,
      isReset = true,
    )
    evaluatedWorksheet
  }

  private def getMdoc(path: AbsolutePath): Mdoc = {
    val mdoc = for {
      target <- buildTargets.inverseSources(path)
      mdoc <- getMdoc(target)
    } yield mdoc
    mdoc.getOrElse(fallabackMdoc)
  }

  private def getMdoc(target: BuildTargetIdentifier): Option[Mdoc] = {

    val key = MdocKey.BuildTarget(target)
    mdocs.get(key).map(_.value).orElse {
      for {
        info <- buildTargets.scalaTarget(target)
        scalaVersion = info.scalaVersion
      } yield {
        // We filter out NonUnitStatements from wartremover or you'll get an
        // error about $doc.binder returning Unit from mdoc, which causes the
        // worksheet not to be evaluated.
        val scalacOptions = info.scalac.getOptions.asScala
          .filterNot(_.contains("semanticdb"))
          .filterNot(_.contains("-Wconf"))
          .filterNot(_.contains("org.wartremover.warts.NonUnitStatements"))
          .asJava
        val mdoc = embedded
          .mdoc(info.scalaVersion)
          .withClasspath(info.fullClasspath.distinct.asJava)
          .withScalacOptions(scalacOptions)
        mdocs(key) = MdocRef(scalaVersion, mdoc)
        mdoc
      }
    }
  }

  private def calculateDigest(classpath: List[Path]): String = {
    val digest = MessageDigest.getInstance("MD5")
    classpath.foreach { path =>
      digest.update(path.toString.getBytes(StandardCharsets.UTF_8))
    }
    MD5.bytesToHex(digest.digest())
  }
}

object WorksheetProvider {

  sealed trait MdocKey
  object MdocKey {
    final case class BuildTarget(id: BuildTargetIdentifier) extends MdocKey
    case object Default extends MdocKey
  }
  final case class MdocRef(scalaVersion: String, value: Mdoc)

  def worksheetScala3AdjustmentsForPC(
      originInput: Input.VirtualFile
  ): Option[(Input.VirtualFile, AdjustLspData)] = {
    val ident = "  "
    val withOuter = s"""|object worksheet{
                        |$ident${originInput.value.replace("\n", "\n" + ident)}
                        |}""".stripMargin
    val modifiedInput =
      originInput.copy(value = withOuter)
    val adjustLspData = AdjustedLspData.create(
      pos => {
        new Position(pos.getLine() - 1, pos.getCharacter() - ident.size)
      },
      filterOutLocations = { loc => !loc.getUri().isWorksheet },
    )
    Some((modifiedInput, adjustLspData))
  }

  def worksheetScala3Adjustments(
      originInput: Input.VirtualFile
  ): Option[(Input.VirtualFile, Position => Position, AdjustLspData)] = {
    worksheetScala3AdjustmentsForPC(originInput).map { case (input, adjust) =>
      def adjustRequest(position: Position) = new Position(
        position.getLine() + 1,
        position.getCharacter() + 2,
      )
      (input, adjustRequest, adjust)

    }
  }
}
