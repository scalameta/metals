package scala.meta.internal.worksheets

import scala.meta._
import scala.meta.internal.decorations.DecorationOptions
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Cancelable
import scala.collection.concurrent.TrieMap
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}
import scala.meta.internal.decorations.ThemableDecorationInstanceRenderOptions
import scala.meta.internal.decorations.ThemableDecorationAttachmentRenderOptions
import scala.concurrent.Future
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.pc.CompilerJobQueue
import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContext
import java.util.concurrent.ScheduledExecutorService
import scala.meta.internal.metals.MetalsSlowTaskParams
import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.pc.InterruptException
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Embedded
import mdoc.{interfaces => i}
import mdoc.interfaces.Mdoc

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
    embedded: Embedded
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
  private val mdocs = new TrieMap[BuildTargetIdentifier, Mdoc]()

  def onBuildTargetDidCompile(target: BuildTargetIdentifier): Unit = {
    clearBuildTarget(target)
  }

  private def clearBuildTarget(target: BuildTargetIdentifier): Unit = {
    mdocs.remove(target).foreach(_.shutdown())
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

  def decorations(
      path: AbsolutePath,
      token: CancelToken
  ): Future[Array[DecorationOptions]] = {
    val result = new CompletableFuture[Array[DecorationOptions]]()
    def completeEmptyResult() = result.complete(Array.empty)
    token.onCancel().asScala.foreach { isCancelled =>
      if (isCancelled) {
        completeEmptyResult()
      }
    }
    val onError: PartialFunction[Throwable, Array[DecorationOptions]] = {
      case NonFatal(e) =>
        scribe.error(s"worksheet: $path", e)
        Array.empty
      case InterruptException() =>
        Array.empty
    }
    def runEvaluation(): Unit = {
      cancelables.cancel() // Cancel previous worksheet evaluations.
      val timer = new Timer(Time.system)
      result.asScala.foreach { _ =>
        scribe.info(s"time: evaluated worksheet '${path.filename}' in $timer")
      }
      cancelables.add(Cancelable(() => completeEmptyResult()))
      statusBar.trackFuture(
        s"Evaluting ${path.filename}",
        result.asScala,
        showTimer = true
      )
      token.checkCanceled()
      // NOTE(olafurpg) Run evaluation in a custom thread so that we can
      // `Thread.stop()` it in case of infinite loop. I'm not aware of any
      // other JVM APIs that allow killing a runnable even in the face of
      // infinite loops.
      val thread = new Thread(s"Evaluating Worksheet ${path.filename}") {
        override def run(): Unit = {
          result.complete(evaluateWorksheet(path, token))
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
        catch onError
      }
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
      result: CompletableFuture[Array[DecorationOptions]],
      thread: Thread
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
              secondsElapsed = userConfig().worksheetCancelTimeout
            )
          )
          cancel.asScala.foreach { c =>
            if (c.cancel && thread.isAlive()) {
              // User has requested to cancel a running program. first line of
              // defense is `Thread.interrupt()`. Fingers crossed it's enough.
              result.complete(Array.empty)
              threadStopper.schedule(stopThread, 1, TimeUnit.SECONDS)
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
      TimeUnit.SECONDS
    )
  }

  private def evaluateWorksheet(
      path: AbsolutePath,
      token: CancelToken
  ): Array[DecorationOptions] = {
    val decorations = for {
      mdoc <- getMdoc(path)
      input = path.toInputFromBuffers(buffers)
    } yield mdoc.evaluateWorksheet(input.path, input.value)
    decorations match {
      case None => Array.empty
      case Some(worksheet) =>
        diagnostics.onPublishDiagnostics(
          path,
          worksheet.diagnostics().iterator().asScala.map(toLsp).toSeq,
          isReset = true
        )
        worksheet
          .statements()
          .iterator()
          .asScala
          .map { s =>
            new DecorationOptions(
              toLsp(s.position()),
              new l.MarkedString("scala", s.details()),
              ThemableDecorationInstanceRenderOptions(
                after = ThemableDecorationAttachmentRenderOptions(
                  s.summary(),
                  color = "green",
                  fontStyle = "italic"
                )
              )
            )
          }
          .toArray
    }
  }

  private def toLsp(p: i.RangePosition): l.Range = {
    new l.Range(
      new l.Position(
        p.startLine(),
        p.startColumn()
      ),
      new l.Position(
        p.endLine(),
        p.endColumn()
      )
    ),
  }
  private def toLsp(d: i.Diagnostic): l.Diagnostic = {
    new l.Diagnostic(
      toLsp(d.position()),
      d.message(),
      d.severity() match {
        case i.DiagnosticSeverity.Info => l.DiagnosticSeverity.Information
        case i.DiagnosticSeverity.Warning => l.DiagnosticSeverity.Warning
        case i.DiagnosticSeverity.Error => l.DiagnosticSeverity.Error
        case _ => l.DiagnosticSeverity.Error
      },
      "mdoc"
    )

  }

  private def getMdoc(path: AbsolutePath): Option[Mdoc] = {
    for {
      target <- buildTargets.inverseSources(path)
      mdoc <- getMdoc(target)
    } yield mdoc
  }
  private def getMdoc(target: BuildTargetIdentifier): Option[Mdoc] = {
    mdocs.get(target).orElse {
      for {
        info <- buildTargets.scalaTarget(target)
        scala <- info.info.asScalaBuildTarget
        scalaVersion = scala.getScalaVersion
        isSupported = ScalaVersions.isSupportedScalaVersion(scalaVersion)
        _ = {
          if (!isSupported) {
            scribe.warn(
              s"worksheet: unsupported Scala version '${scalaVersion}', to fix this problem use Scala version '${BuildInfo.scala212}' instead."
            )
          }
        }
        if isSupported
      } yield {
        val scalacOptions = info.scalac.getOptions.asScala
          .filterNot(_.contains("semanticdb"))
          .asJava
        val mdoc = embedded
          .mdoc(scala)
          .withClasspath(info.fullClasspath.asScala.distinct.asJava)
          .withScalacOptions(scalacOptions)
        mdocs(target) = mdoc
        mdoc
      }
    }
  }

}
