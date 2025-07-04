package scala.meta.internal.worksheets

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import scala.meta._
import scala.meta.internal.metals.AdjustLspData
import scala.meta.internal.metals.AdjustedLspData
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
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
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages

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
    workDoneProgress: WorkDoneProgress,
    diagnostics: Diagnostics,
    embedded: Embedded,
    compilations: Compilations,
    scalaVersionSelector: ScalaVersionSelector,
    clientConfig: ClientConfiguration,
)(implicit ec: ExecutionContext)
    extends Cancelable {
  private val serverConfig: MetalsServerConfig = clientConfig.initialConfig

  case class EvaluatedWorksheetSnapshot(
      evaluatedWorksheet: EvaluatedWorksheet,
      text: String,
  )
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
  private val exportableEvaluations =
    new TrieMap[AbsolutePath, Promise[EvaluatedWorksheetSnapshot]]()
  private val worksheetPcData =
    new TrieMap[AbsolutePath, WorksheetPcData]()

  private def fallabackMdoc: Mdoc = {
    val scalaVersion =
      scalaVersionSelector.fallbackScalaVersion()
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

  private def evaluateAndPublish[T](
      path: AbsolutePath,
      token: CancelToken,
      publish: Option[EvaluatedWorksheetSnapshot] => T,
  ): Future[T] = {
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
    }).flatMap(_ => evaluateAsync(path, token).map(publish))
  }

  def evaluateAndPublish(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[Unit] = {
    evaluateAndPublish(
      path,
      token,
      _.foreach { toPublish =>
        if (clientConfig.isInlayHintsRefreshEnabled())
          languageClient.refreshInlayHints()
      },
    )
  }

  def inlayHints(
      path: Option[AbsolutePath],
      token: CancelToken,
  ): Future[List[InlayHint]] = {
    path match {
      case Some(path) => inlayHints(path, token)
      case None => Future.successful(Nil)
    }
  }

  def inlayHints(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[List[InlayHint]] = if (path.isWorksheet) {
    exportableEvaluations.get(path) match {
      case Some(value) if value.isCompleted =>
        value.future.map(worksheet => toInlayHints(path, Some(worksheet)))
      case Some(value) =>
        if (clientConfig.isInlayHintsRefreshEnabled()) {
          value.future.map { _ =>
            languageClient.refreshInlayHints()
            Nil
          }
        } else {
          value.future.map(worksheet => toInlayHints(path, Some(worksheet)))
        }
      case None =>
        evaluateAndPublish(
          path,
          token,
          toInlayHints(path, _),
        )
    }
  } else Future.successful(Nil)

  private def toInlayHints(
      path: AbsolutePath,
      worksheet: Option[EvaluatedWorksheetSnapshot],
  ) = {
    worksheet match {
      case None => Nil
      case Some(value) =>
        val distance =
          buffers.tokenEditDistance(path, value.text, scalaVersionSelector)
        value.evaluatedWorksheet
          .statements()
          .map { stat =>
            val statEnd = stat.position().toLsp.getEnd()
            // Update positions so that they don't break
            distance.toRevised(statEnd).foreach { right =>
              statEnd.setLine(right.startLine)
              statEnd.setCharacter(right.startColumn)
            }
            val hint = new InlayHint(
              statEnd,
              messages.Either.forLeft(" // " + truncatify(stat)),
            )
            hint.setTooltip(stat.details())
            hint
          }
          .asScala
          .toList
    }
  }

  /**
   * Check to see for a given path if there is an evaluated worksheet that
   * matches thie exact input.
   *
   * @param path to the input used to search previous evaluations.
   * @return possible evaluated output.
   */
  def copyWorksheetOutput(path: AbsolutePath): Future[Option[String]] = {
    lazy val input = path.toInputFromBuffers(buffers)
    exportableEvaluations.get(path) match {
      case None => Future.successful(None)
      case Some(worksheetPromise) =>
        worksheetPromise.future.map { worksheetSnapshot =>
          val toExport = new StringBuilder
          val originalLines = input.value.split("\n").toBuffer.zipWithIndex

          originalLines.foreach { case (lineContent, lineNumber) =>
            val lineHasOutput = worksheetSnapshot.evaluatedWorksheet
              .statements()
              .asScala
              .find(_.position.endLine() == lineNumber)

            lineHasOutput match {
              case Some(statement) =>
                val detailLines = statement
                  .prettyDetails()
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
  }

  private def evaluateAsync(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[Option[EvaluatedWorksheetSnapshot]] = {
    val result = new CompletableFuture[Option[EvaluatedWorksheetSnapshot]]()
    def completeEmptyResult() = result.complete(None)
    token.onCancel().asScala.foreach { isCancelled =>
      if (isCancelled) {
        completeEmptyResult()
      }
    }
    val onError
        : PartialFunction[Throwable, Option[EvaluatedWorksheetSnapshot]] = {
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
      workDoneProgress.trackFuture(
        s"Evaluating ${path.filename}",
        result.asScala,
      )
      token.checkCanceled()
      val promise = Promise[EvaluatedWorksheetSnapshot]()
      exportableEvaluations.update(path, promise)
      // NOTE(olafurpg) Run evaluation in a custom thread so that we can
      // `Thread.stop()` it in case of infinite loop. I'm not aware of any
      // other JVM APIs that allow killing a runnable even in the face of
      // infinite loops.
      val thread = new Thread(s"Evaluating Worksheet ${path.filename}") {
        override def run(): Unit = {
          result.complete(
            try Some(evaluateWorksheet(path, promise))
            catch onError
          )
        }
      }
      val cancellable = toCancellable(thread, result)
      interruptThreadOnCancel(path, result, cancellable)

      thread.start()
      val timeout = serverConfig.worksheetTimeout * 1000
      thread.join(timeout)
      if (!result.isDone()) {
        cancellable.cancel()
        languageClient.showMessage(
          MessageType.Warning,
          Messages.worksheetTimeout,
        )
      }
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

  private def toCancellable(
      thread: Thread,
      result: CompletableFuture[Option[EvaluatedWorksheetSnapshot]],
  ): Cancelable = {
    // Last resort, if everything else fails we use `Thread.stop()`.
    val stopThread = new Runnable {
      def run(): Unit = {
        if (thread.isAlive()) {
          scribe.warn(s"thread stop: ${thread.getName()}")
          thread.stop()
        }
      }
    }
    new Cancelable {
      def cancel() =
        if (thread.isAlive()) {
          // Canceling a running program. first line of
          // defense is `Thread.interrupt()`. Fingers crossed it's enough.
          result.complete(None)
          threadStopper.schedule(stopThread, 3, TimeUnit.SECONDS)
          scribe.warn(s"thread interrupt: ${thread.getName()}")
          thread.interrupt()
        }
    }
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
      result: CompletableFuture[Option[EvaluatedWorksheetSnapshot]],
      cancellable: Cancelable,
  ): Unit = {
    // If the program is running for more than
    // `userConfig().worksheetCancelTimeout`, then display a prompt for the user
    // to cancel the program.
    val interruptThread = new Runnable {
      def run(): Unit = {
        if (!result.isDone()) {
          val (task, token) = workDoneProgress.startProgress(
            s"Evaluating worksheet '${path.filename}'",
            onCancel = Some(cancellable.cancel),
          )

          result.asScala.onComplete { _ =>
            task.wasFinished.set(true)
            workDoneProgress.endProgress(token)
          }
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
      path: AbsolutePath,
      promise: Promise[EvaluatedWorksheetSnapshot],
  ): EvaluatedWorksheetSnapshot = {
    val mdoc = getMdoc(path)
    val originId = "METALS-$" + UUID.randomUUID().toString
    val input = path.toInputFromBuffers(buffers)
    val relativePath = path.toRelative(workspace)
    val evaluatedWorksheet =
      mdoc.evaluateWorksheet(relativePath.toString(), input.value)
    val worksheetSnapshot =
      EvaluatedWorksheetSnapshot(evaluatedWorksheet, input.value)
    promise.trySuccess(worksheetSnapshot)

    val oldDigest = worksheetPcData.get(path).getOrElse("")
    val classpath = evaluatedWorksheet.classpath().asScala.toList
    val newDigest = calculateDigest(classpath)
    if (oldDigest != newDigest) {
      worksheetPcData.put(
        path,
        WorksheetPcData(
          newDigest,
          getDependencies(evaluatedWorksheet),
          classpath,
        ),
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
      originId = originId,
    )
    worksheetSnapshot
  }

  def getWorksheetPCData(path: AbsolutePath): Option[WorksheetPcData] =
    worksheetPcData.get(path)

  private def getDependencies(
      evaluatedWorksheet: EvaluatedWorksheet
  ) =
    fetchDependencySources(
      evaluatedWorksheet.dependencies().asScala.toSeq
    ).filter(_.toString().endsWith("-sources.jar"))

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
          // seems to break worksheet support
          .filterNot(_.contains("Ycheck-reentrant"))
          .filterNot(_.contains("org.wartremover.warts.NonUnitStatements"))
          .asJava
        try {
          val awaitClasspath = Await.result(
            buildTargets
              .targetClasspath(target, Promise[Unit]())
              .getOrElse(Future.successful(Nil)),
            Duration(
              5,
              TimeUnit.SECONDS,
            ),
          )
          val classpath =
            awaitClasspath.map(pathString => pathString.toAbsolutePath.toNIO)
          val mdoc = embedded
            .mdoc(info.scalaVersion)
            .withClasspath(classpath.distinct.asJava)
            .withScalacOptions(scalacOptions)
          mdocs(key) = MdocRef(scalaVersion, mdoc)
          mdoc
        } catch {
          case _: TimeoutException =>
            scribe.warn(
              s"Still waiting for information about classpath, using default worksheet with empty classpath"
            )
            fallabackMdoc
        }
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
    val withOuter =
      s"""object worksheet{\n$ident${originInput.value.replace("\n", "\n" + ident)}\n}"""
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

case class WorksheetPcData(
    digest: String,
    dependencies: List[Path],
    classpath: List[Path],
)
