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
import mdoc.internal.cli.Context
import mdoc.internal.markdown.SectionInput
import mdoc.internal.markdown.Modifier
import mdoc.internal.markdown.Instrumenter
import mdoc.internal.markdown.MarkdownCompiler
import org.eclipse.{lsp4j => l}
import scala.meta.internal.decorations.ThemableDecorationInstanceRenderOptions
import scala.meta.internal.decorations.ThemableDecorationAttachmentRenderOptions
import mdoc.internal.cli.Settings
import scala.meta.internal.metals.ScalaTarget
import scala.concurrent.Future
import scala.meta.pc.CancelToken
import pprint.TPrintColors
import scala.meta.internal.metals.UserConfiguration
import scala.collection.immutable.Nil
import mdoc.document.Statement
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
import pprint.PPrinter.BlackWhite
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time

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
    diagnostics: Diagnostics
)(implicit ec: ExecutionContext)
    extends Cancelable {

  private val commentHeader = " // "
  // The smallest column width that worksheet values will used for rendering
  // worksheet decorations.
  private val minimumMargin = 20
  // Worksheet evaluation happens on a single threaded job queue. Jobs are
  // prioritized using the same order as completion/hover requests:
  // first-come last-out.
  private lazy val jobs = CompilerJobQueue()
  // Executor for stopping threads. We don't reuse the scheduled executor from
  // MetalsLanguageServer because this exector service may occasionally block
  // and we don't want to block on other features like the status bar.
  private lazy val threadStopper: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  private val cancelables = new MutableCancelable()
  private val contexts = new TrieMap[BuildTargetIdentifier, Context]()
  private val reporter = new MdocStoreReporter()

  def onBuildTargetDidCompile(target: BuildTargetIdentifier): Unit = {
    clearBuildTarget(target)
  }

  private def clearBuildTarget(target: BuildTargetIdentifier): Unit = {
    contexts.remove(target).foreach(_.compiler.global.close())
  }

  def reset(): Unit = {
    contexts.keysIterator.foreach(clearBuildTarget)
  }

  def cancel(): Unit = {
    jobs.shutdown()
    threadStopper.shutdown()
    reset()
  }

  def decorations(
      path: AbsolutePath,
      token: CancelToken
  ): Future[Array[DecorationOptions]] = {
    reporter.reset()
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
      cancelables.add(
        Cancelable(() => {
          if (thread.isAlive) {
            thread.stop()
          }
        })
      )
      stopThreadOnCancel(path, result, thread)
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
  private def stopThreadOnCancel(
      path: AbsolutePath,
      result: CompletableFuture[Array[DecorationOptions]],
      thread: Thread
  ): Unit = {
    // Last resort, if everything else fails we use `Thread.stop()`.
    val stopThread = new Runnable {
      def run(): Unit = {
        if (thread.isAlive()) {
          scribe.warn(s"thread stop: ${thread.getName()}")
          Cancelable.cancelAll(
            List(
              Cancelable(() => thread.stop()),
              cancelables
            )
          )
        }
      }
    }
    // If the program is running for more than
    // `userConfig().worksheetCancelTimeout`, then display a prompt for the user
    // to cancel the program.
    val promptUserToCancel = new Runnable {
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
      promptUserToCancel,
      userConfig().worksheetCancelTimeout,
      TimeUnit.SECONDS
    )
  }

  private def renderHoverMessage(
      statement: Statement,
      margin: Int,
      isEmptyValue: Boolean
  ): String = {
    val out = new StringBuilder()
    if (!isEmptyValue) {
      statement.binders.iterator.foreach { binder =>
        out
          .append("\n")
          .append(binder.name)
          .append(": ")
          .append(binder.tpe.render(TPrintColors.BlackWhite))
          .append(" = ")
        BlackWhite
          .tokenize(binder.value, width = 100)
          .foreach(text => out.appendAll(text.getChars))
      }
    }
    statement.out.linesIterator.foreach { line =>
      out.append("\n// ").append(line)
    }
    out.toString()
  }

  private def renderContentText(
      statement: Statement,
      margin: Int,
      isEmptyValue: Boolean
  ): String = {
    val out = new StringBuilder()
    out.append(commentHeader)
    if (isEmptyValue) {
      if (!statement.out.isEmpty()) {
        out.append(statement.out.linesIterator.next())
      }
    } else {
      val isSingle = statement.binders.lengthCompare(1) == 0
      statement.binders.iterator.zipWithIndex.foreach {
        case (binder, i) =>
          if (!isSingle) {
            out
              .append(if (i == 0) "" else ", ")
              .append(binder.name)
              .append("=")
          }
          val truncatedLine = BlackWhite
            .tokenize(binder.value, width = margin)
            .map(_.getChars)
            .filterNot(_.iterator.forall(_.isWhitespace))
            .flatMap(_.iterator)
            .filter {
              case '\n' => false
              case _ => true
            }
            .take(margin)
          out.appendAll(truncatedLine)
      }
    }
    out.toString()
  }

  private def evaluateWorksheet(
      path: AbsolutePath,
      token: CancelToken
  ): Array[DecorationOptions] = {
    val input = path.toInputFromBuffers(buffers)
    // NOTE(olafurpg): the sbt dialects is the closest available syntax to worksheets.
    val WorksheetDialect = dialects.Sbt1
    val decorations = for {
      ctx <- getContext(path)
      source <- WorksheetDialect(input).parse[Source].toOption
    } yield renderDecorations(path, ctx, source)
    decorations.getOrElse(Array.empty)
  }

  private def renderDecorations(
      path: AbsolutePath,
      ctx: Context,
      source: Source
  ): Array[DecorationOptions] = {
    val sectionInput = SectionInput(
      path.toInputFromBuffers(buffers),
      source,
      Modifier.Default()
    )
    val sectionInputs = List(sectionInput)
    val instrumented = Instrumenter.instrument(sectionInputs)
    val rendered = MarkdownCompiler.buildDocument(
      ctx.compiler,
      ctx.reporter,
      sectionInputs,
      instrumented,
      path.toString
    )

    val decorations = for {
      section <- rendered.sections.iterator
      statement <- section.section.statements
    } yield renderDecoration(statement)

    diagnostics.onPublishDiagnostics(
      path,
      reporter.diagnostics.toSeq,
      isReset = true
    )

    decorations
      .filterNot(_.renderOptions.after.contentText == commentHeader)
      .toArray
  }

  private def renderDecoration(statement: Statement): DecorationOptions = {
    val pos = statement.position
    val range = new l.Range(
      new l.Position(pos.startLine, pos.startColumn),
      new l.Position(pos.endLine, pos.endColumn)
    )
    val margin = math.max(
      minimumMargin,
      userConfig().worksheetScreenWidth - statement.position.endColumn
    )
    val isEmptyValue = isUnitType(statement) || statement.binders.isEmpty
    val contentText = renderContentText(statement, margin, isEmptyValue)
    val hoverMessage = renderHoverMessage(statement, margin, isEmptyValue)
    DecorationOptions(
      range,
      new l.MarkedString("scala", hoverMessage),
      ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          contentText,
          color = "green",
          fontStyle = "italic"
        )
      )
    )
  }

  private def getContext(path: AbsolutePath): Option[Context] = {
    for {
      target <- buildTargets.inverseSources(path)
      info <- buildTargets.scalaTarget(target)
      scala <- info.info.asScalaBuildTarget
      scalaVersion = scala.getScalaVersion
      isSupported = ScalaVersions.isCurrentScalaCompilerVersion(scalaVersion)
      _ = {
        if (!isSupported) {
          scribe.warn(
            s"worksheet: unsupported Scala version '${scalaVersion}', to fix this problem use Scala version '${BuildInfo.scala212}' instead."
          )
        }
      }
      if isSupported
    } yield contexts.getOrElseUpdate(target, newContext(target, info))
  }

  private def newContext(
      target: BuildTargetIdentifier,
      info: ScalaTarget
  ): Context = {
    scribe.info(s"worksheet: new compiler for ${target.getUri()}")
    val settings = Settings.default(workspace)
    val classpath = Classpath(info.fullClasspath).syntax
    val options = info.scalac.getOptions().asScala.mkString(" ")
    val compiler = MarkdownCompiler.fromClasspath(classpath, options)
    Context(settings, reporter, compiler)
  }

  private def isUnitType(statement: Statement): Boolean = {
    statement.binders match {
      case head :: Nil => head.value == ()
      case _ => false
    }

  }
}
