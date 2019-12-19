package scala.meta.internal.metals.debug

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.StoppedEventArguments
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import tests.DapTestEnrichments._
import scala.util.Failure
import scala.util.Success

final class TestDebugger(
    connect: RemoteServer.Listener => Debugger,
    onStoppage: Stoppage.Handler
)(implicit ec: ExecutionContext)
    extends RemoteServer.Listener {
  @volatile private var debugger = connect(this)
  @volatile private var terminated: Promise[Unit] = Promise()
  @volatile private var output = new DebuggeeOutput
  @volatile private var breakpoints = new DebuggeeBreakpoints()

  @volatile private var failure: Option[Throwable] = None

  def initialize: Future[Capabilities] = {
    ifNotFailed(debugger.initialize)
  }

  def launch: Future[Unit] = {
    ifNotFailed(debugger.launch)
  }

  def configurationDone: Future[Unit] = {
    ifNotFailed(debugger.configurationDone)
  }

  def setBreakpoints(
      path: AbsolutePath,
      positions: List[Position]
  ): Future[SetBreakpointsResponse] = {
    val source = path.toDAP
    val breakpoints = positions.map(_.toBreakpoint).toArray
    ifNotFailed(debugger.setBreakpoints(source, breakpoints))
      .map { response =>
        // the breakpoint notification we receive does not contain the source
        // hence we have to register breakpoints here
        response.getBreakpoints.foreach(this.breakpoints.register)
        response
      }
  }

  def restart: Future[Unit] = {
    ifNotFailed(debugger.restart).andThen {
      case _ =>
        debugger = connect(this)
        terminated = Promise()
        output = new DebuggeeOutput
        breakpoints = new DebuggeeBreakpoints
    }
  }

  def disconnect: Future[Unit] = {
    ifNotFailed(debugger.disconnect)
  }

  /**
   * Not waiting for exited because it might not be sent
   */
  def shutdown: Future[Unit] = {
    for {
      _ <- terminated.future
      _ <- debugger.shutdown
      _ <- onStoppage.shutdown
    } yield ()
  }

  def awaitOutput(prefix: String, seconds: Int = 5): Future[Unit] = {
    ifNotFailed {
      output
        .awaitPrefix(prefix.replaceAll("\n", System.lineSeparator()))
        .withTimeout(seconds, TimeUnit.SECONDS)
        .recoverWith {
          case timeout: TimeoutException =>
            val error = s"No prefix [$prefix] in [${output()}]"
            Future.failed(new Exception(error, timeout))
        }
    }
  }

  def allOutput: Future[String] = {
    terminated.future.map(_ => output())
  }

  override def onOutput(event: OutputEventArguments): Unit = {
    import org.eclipse.lsp4j.debug.{OutputEventArgumentsCategory => Category}
    event.getCategory match {
      case Category.STDOUT =>
        output.append(event.getOutput)
      case Category.STDERR =>
        fail(new IllegalStateException(event.getOutput))
      case _ =>
      // ignore
    }
  }

  override def onTerminated(): Unit = {
    terminated.trySuccess(()) // might already be completed in [[fail]]
  }

  override def onStopped(event: StoppedEventArguments): Unit = {
    val nextStep = for {
      frame <- ifNotFailed(debugger.stackFrame(event.getThreadId))
      cause <- findStoppageCause(event, frame)
      nextStep <- onStoppage(Stoppage(frame, cause))
    } yield nextStep

    nextStep.onComplete {
      case Failure(error) =>
        fail(error)
      case Success(step) =>
        debugger.step(event.getThreadId, step).recover {
          case error => fail(error)
        }
    }
  }

  private def findStoppageCause(
      event: StoppedEventArguments,
      frame: StackFrame
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
              case Some(error) => // propagate failure that occurred while processing action
                Future.failed(error)
              case None =>
                Future.successful(value)
            }
        }
    }
  }

  private def fail(error: Throwable): Unit = {
    if (failure.isEmpty) {
      failure = Some(error)
      terminated.tryFailure(error)
      disconnect.andThen { case _ => debugger.shutdown }
    }
  }
}

object TestDebugger {
  private val timeout = TimeUnit.SECONDS.toMillis(60).toInt

  def apply(uri: URI, stoppageHandler: Stoppage.Handler)(
      implicit ec: ExecutionContext
  ): TestDebugger = {
    def connect(listener: RemoteServer.Listener): Debugger = {
      val socket = new Socket()
      socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), timeout)
      val server = RemoteServer(socket, listener)
      new Debugger(server)
    }

    new TestDebugger(connect, stoppageHandler)
  }
}
