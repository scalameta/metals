package scala.meta.internal.metals.debug.server.testing

import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Try
import scala.util.control.NonFatal

import ch.epfl.scala.debugadapter.testing.TestSuiteEvent
import sbt.ForkConfiguration
import sbt.ForkTags
import sbt.testing.Event
import sbt.testing.Framework
import sbt.testing.TaskDef

/**
 * Implements the protocol that the forked remote JVM talks with the host process.
 *
 * This protocol is not formal and has been implemented after sbt's `ForkTests`.
 */
final class TestServer(
    eventHandler: LoggingEventHandler,
    classLoader: ClassLoader,
    discoveredTests: Map[Framework, List[TaskDef]],
)(implicit ec: ExecutionContext) {

  private val server = new ServerSocket(0)
  private val (runners, tasks) = {
    def getRunner(framework: Framework) = {
      val frameworkClass = framework.getClass.getName
      frameworkClass -> TestInternals.getRunner(framework, classLoader)
    }
    // Return frameworks and tasks in order to ensure a deterministic test execution
    val sorted = discoveredTests.toList.sortBy(_._1.name())
    (
      sorted.map(_._1).map(getRunner),
      sorted.flatMap(_._2.sortBy(_.fullyQualifiedName())),
    )
  }

  case class TestOrchestrator(startServer: Future[Unit], reporter: Future[Unit])
  val port = server.getLocalPort
  def listenToTests: TestOrchestrator = {
    def forkFingerprint(td: TaskDef): TaskDef = {
      val newFingerprint =
        sbt.SerializableFingerprints.forkFingerprint(td.fingerprint)
      new TaskDef(
        td.fullyQualifiedName,
        newFingerprint,
        td.explicitlySpecified,
        td.selectors,
      )
    }

    @annotation.tailrec
    def receiveLogs(is: ObjectInputStream, os: ObjectOutputStream): Unit = {
      is.readObject() match {
        case Array(ForkTags.`Error`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Error(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Warn`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Warn(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Info`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Info(s))
          receiveLogs(is, os)
        case Array(ForkTags.`Debug`, s: String) =>
          eventHandler.handle(TestSuiteEvent.Debug(s))
          receiveLogs(is, os)
        case t: Throwable =>
          eventHandler.handle(TestSuiteEvent.Trace(t))
          receiveLogs(is, os)
        case Array(testSuite: String, events: Array[Event]) =>
          eventHandler.handle(TestSuiteEvent.Results(testSuite, events.toList))
          receiveLogs(is, os)
        case ForkTags.`Done` =>
          eventHandler.handle(TestSuiteEvent.Done)
          os.writeObject(ForkTags.Done)
          os.flush()
      }
    }

    def talk(
        is: ObjectInputStream,
        os: ObjectOutputStream,
        config: ForkConfiguration,
    ): Unit = {
      try {
        os.writeObject(config)
        val taskDefs = tasks.map(forkFingerprint)
        os.writeObject(taskDefs.toArray)
        os.writeInt(runners.size)
        taskDefs.foreach { taskDef =>
          taskDef.fingerprint()
        }

        val taskDefsDescription = taskDefs.map { taskDef =>
          val selectors =
            taskDef.selectors().toList.map(_.toString()).mkString("(", ",", ")")
          s"${taskDef.fullyQualifiedName()}$selectors"
        }
        scribe.debug(s"Sent task defs to test server: $taskDefsDescription")

        runners.foreach { case (frameworkClass, runner) =>
          scribe.debug(
            s"Sending runner to test server: ${frameworkClass} ${runner.args.toList}"
          )
          os.writeObject(Array(frameworkClass))
          os.writeObject(runner.args)
          os.writeObject(runner.remoteArgs)
        }

        os.flush()
        receiveLogs(is, os)
      } catch {
        case NonFatal(t) =>
          scribe.error(s"Failed to initialize communication: ${t.getMessage}")
          scribe.trace(t)
      }
    }

    val serverStarted = Promise[Unit]()
    val clientConnection = Future {
      scribe.debug(s"Firing up test server at $port. Waiting for client...")
      serverStarted.trySuccess(())
      server.accept()
    }

    val testListeningTask = clientConnection.flatMap { socket =>
      scribe.debug("Test server established connection with remote JVM.")
      val os = new ObjectOutputStream(socket.getOutputStream)
      os.flush()
      val is = new ObjectInputStream(socket.getInputStream)
      val config = new ForkConfiguration(
        /* ansiCodesSupported = */ false, /* parallel = */ false,
      )

      @volatile var alreadyClosed: Boolean = false
      def cleanSocketResources() = Future {
        if (!alreadyClosed) {
          for {
            _ <- Try(is.close())
            _ <- Try(os.close())
            _ <- Try(socket.close())
          } yield {
            alreadyClosed = false
          }
          ()
        }
      }

      val talkFuture = Future(talk(is, os, config))
      talkFuture.onComplete(_ => cleanSocketResources())
      talkFuture
    }

    def closeServer(t: Option[Throwable], fromCancel: Boolean) = Future {
      t.foreach {
        case NonFatal(e) =>
          scribe.error(
            s"Unexpected error during remote test execution: '${e.getMessage}'."
          )
          scribe.trace(e)
        case _ =>
      }

      runners.foreach(_._2.done())

      server.close()
      // Do both just in case the logger streams have been closed by nailgun
      if (fromCancel) {
        // opts.ngout.println("The test execution was successfully cancelled.")
        scribe.debug("Test server has been successfully cancelled.")
      } else {
        // opts.ngout.println("The test execution was successfully closed.")
        scribe.debug("Test server has been successfully closed.")
      }
    }

    val listener = {
      testListeningTask.onComplete {
        case Failure(exception) => closeServer(Some(exception), true)
        case _ => closeServer(None, false)
      }
      testListeningTask
    }

    TestOrchestrator(serverStarted.future, listener)
  }
}

case class TestArgument(args: List[String], frameworkNames: List[String])
