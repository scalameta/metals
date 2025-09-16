package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Success

import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.debugadapter.TestResultEvent
import ch.epfl.scala.debugadapter.testing.SingleTestResult
import com.google.gson.JsonElement
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage

final class TestDebugger(
    connect: RemoteServer.Listener => Debugger,
    onStoppage: Stoppage.Handler,
    requestOtherThreadStackTrace: Boolean = false,
)(implicit ec: ExecutionContext)
    extends RemoteServer.Listener {
  @volatile private var debugger = connect(this)
  @volatile private var terminated: Promise[Unit] = Promise()
  @volatile private var output = new DebuggeeOutput
  @volatile private var breakpoints = new DebuggeeBreakpoints()
  private val events =
    scala.collection.mutable.ListBuffer.empty[TestResultEvent]

  @volatile private var failure: Option[Throwable] = None

  def initialize: Future[Capabilities] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.initialize)
  }

  def launch: Future[Unit] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.launch(debug = true))
  }

  def attach(port: Int): Future[Unit] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.attach(port))
  }

  def launch(debug: Boolean): Future[Unit] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.launch(debug))
  }

  def configurationDone: Future[Unit] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.configurationDone)
  }

  def setBreakpoints(
      source: Source,
      positions: List[Int],
  ): Future[SetBreakpointsResponse] = {
    val failed = positions.map { line =>
      val breakpoint = new SourceBreakpoint
      breakpoint.setLine(line + 1) // breakpoints are 1-based
      breakpoint.setColumn(0)
      breakpoint
    }.toArray
    ifNotFailed(debugger.setBreakpoints(source, failed))
      .map { response =>
        // the breakpoint notification we receive does not contain the source
        // hence we have to register breakpoints here
        response.getBreakpoints.foreach { brPoint =>
          // note(@tgodzik) from time to time breakpoints are sent back without the source,
          // it's pretty rare, but we were unable to find the reason
          // more details here https://github.com/scalameta/metals/issues/1569
          if (brPoint.getSource() == null) {
            brPoint.setSource(source)
          }
          this.breakpoints.register(brPoint)
        }
        response
      }
  }

  def restart: Future[Unit] = {
    Debug.printEnclosing()
    ifNotFailed(debugger.restart).andThen { case _ =>
      debugger = connect(this)
      terminated = Promise()
      output = new DebuggeeOutput
      breakpoints = new DebuggeeBreakpoints
    }
  }

  def disconnect: Future[Unit] = {
    ifNotFailed(debugger.disconnect).map(_ => terminated.trySuccess(()))
  }

  /**
   * Not waiting for exited because it might not be sent
   */
  def shutdown: Future[Unit] = {
    Debug.printEnclosing()
    for {
      _ <- terminated.future.withTimeout(60, TimeUnit.SECONDS).recoverWith {
        case _: TimeoutException =>
          terminated.trySuccess(())
          scribe.warn("We never got the terminate message")
          Future.unit
      }
      _ = scribe.info("TestingDebugger terminated")
      _ <- debugger.shutdown(60).recoverWith { case _: TimeoutException =>
        scribe.warn("The debugger is most likely already shut down.")
        Future.unit
      }
      _ = scribe.info("Remote server shutdown")
      _ <- onStoppage.shutdown
    } yield ()
  }

  def awaitOutput(prefix: String, seconds: Int = 5): Future[Unit] = {
    ifNotFailed {
      output
        .awaitPrefix(prefix.replaceAll("\n", System.lineSeparator()))
        .withTimeout(seconds, TimeUnit.SECONDS)
        .recoverWith { case timeout: TimeoutException =>
          val error = s"No prefix [$prefix] in [${output()}]"
          Future.failed(new Exception(error, timeout))
        }
    }
  }

  def allOutput: Future[String] = {
    terminated.future.map(_ => output())
  }
  def allTestEvents: Future[String] = {
    terminated.future.map { _ =>
      events.toList
        .map { event =>
          val results = event.data.tests.asScala
            .map {
              case passed: SingleTestResult.Passed =>
                s"  ${passed.testName} - passed"
              case failed: SingleTestResult.Failed if failed.location != null =>
                s"  ${failed.testName} - failed at ${failed.location.file.toAbsolutePath.filename}, line ${failed.location.line}"
              case failed: SingleTestResult.Failed =>
                s"  ${failed.testName} - failed"
              case skipped: SingleTestResult.Skipped =>
                s"  ${skipped.testName} - skipped"
            }
            .mkString("\n")
          s"""|
              |${event.data.suiteName}
              |${results}
              |""".stripMargin
        }
        .mkString("\n")
    }
  }

  override def onEvent(event: NotificationMessage): Unit = {
    import DapJsonParser._
    if (event.getMethod() == "testResult") {
      Debug.printEnclosing()
      event.getParams match {
        case elem: JsonElement =>
          val testEvent = elem.as[TestResultEvent].get
          events += testEvent
        case _ =>
      }
    }
  }

  override def onOutput(event: OutputEventArguments): Unit = {
    Debug.printEnclosing()
    import org.eclipse.lsp4j.debug.{OutputEventArgumentsCategory => Category}
    event.getCategory match {
      case Category.STDOUT =>
        output.append(event.getOutput)
      case Category.STDERR =>
        val output = event.getOutput()
        // This might sometimes be printed in the JVM, but does not cause any actual issues
        if (
          !output.contains("Picked up JAVA_TOOL_OPTIONS") &&
          !output.contains("transport error 202: send failed: Broken pipe") &&
          !output.contains("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called") &&
          !output.contains("WARNING: sun.misc.Unsafe") &&
          !output.contains("WARNING: Please consider reporting this to the maintainers") &&
          !output.contains("WARNING: Use --enable-native-access=ALL-UNNAMED") &&
          !output.contains("WARNING: Restricted methods will be blocked in a future release")
        )
          fail(new IllegalStateException(output))
      case _ =>
      // ignore
    }
  }

  override def onTerminated(): Unit = {
    Debug.printEnclosing()
    terminated.trySuccess(()) // might already be completed in [[fail]]
  }

  override def onStopped(event: StoppedEventArguments): Unit = {
    Debug.printEnclosing()
    val nextStep = for {
      frame <- ifNotFailed(debugger.stackFrame(event.getThreadId))
      cause <- findStoppageCause(event, frame)
      _ <-
        if (requestOtherThreadStackTrace) {
          debugger
            .threads()
            .flatMap { threadsResponse =>
              val otherThreadsId0 = threadsResponse.getThreads
                .map(_.getId)
                .find(_ != event.getThreadId)
              otherThreadsId0
                .map(debugger.stackTrace(_))
                .getOrElse(Future.successful(new StackTraceResponse()))
            }
        } else {
          Future.successful(new StackTraceResponse())
        }
    } yield onStoppage(Stoppage(frame, cause))

    nextStep.onComplete {
      case Failure(error) =>
        fail(error)
      case Success(step) =>
        debugger.step(event.getThreadId, step).recover { case error =>
          fail(error)
        }
    }
  }

  private def findStoppageCause(
      event: StoppedEventArguments,
      frame: StackFrame,
  ): Future[Stoppage.Cause] = {
    import org.eclipse.lsp4j.debug.{StoppedEventArgumentsReason => Reason}
    event.getReason match {
      case Reason.BREAKPOINT =>
        breakpoints.byStackFrame(frame) match {
          case Some(_) =>
            Future.successful(Stoppage.Cause.Breakpoint)
          case None =>
            val allBreakpoints = breakpoints.all.mkString("\n")
            val error =
              s"No breakpoint for ${frame.info}. Registered breakpoints are $allBreakpoints"
            Future.failed(new IllegalStateException(error))
        }
      case Reason.STEP =>
        Future.successful(Stoppage.Cause.Step)
      case reason =>
        Future.successful(Stoppage.Cause.Other(reason))
    }
  }

  private def ifNotFailed[A](action: => Future[A]): Future[A] = {
    import scala.util.{Failure, Success}
    failure match {
      case Some(error) => // don't start the action if already failed
        Future.failed(error)
      case None =>
        action.andThen {
          case Failure(error) => // fail when the action fails
            failure.foreach(error.addSuppressed)
            fail(error)
            Future.failed(error)
          case Success(value) =>
            failure match {
              case Some(
                    error
                  ) => // propagate failure that occurred while processing action
                Future.failed(error)
              case None =>
                Future.successful(value)
            }
        }
    }
  }

  private def fail(error: Throwable): Unit = {
    Debug.printEnclosing()
    if (failure.isEmpty) {
      failure = Some(error)
      terminated.tryFailure(error)
      disconnect.andThen { case _ => debugger.shutdown() }
    }
  }
}

object TestDebugger {
  private val timeout = TimeUnit.SECONDS.toMillis(60).toInt

  def apply(
      uri: URI,
      stoppageHandler: Stoppage.Handler,
      requestOtherThreadStackTrace: Boolean = false,
  )(implicit
      ec: ExecutionContext
  ): TestDebugger = {
    def connect(listener: RemoteServer.Listener): Debugger = {
      val socket = new Socket()
      socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), timeout)
      val server = RemoteServer(socket, listener)
      new Debugger(server)
    }

    new TestDebugger(connect, stoppageHandler, requestOtherThreadStackTrace)
  }
}
