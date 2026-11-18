package tests.debug

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Promise

import scala.meta.internal.metals.debug.server.Logger
import scala.meta.internal.metals.debug.server.Run
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.testing.TestSuiteSummary
import tests.BaseSuite

class RunMainSuite extends BaseSuite {
  private implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override def afterAll(): Unit = {
    ec.shutdown()
    try {
      if (!ec.awaitTermination(5, TimeUnit.SECONDS)) {
        ec.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        ec.shutdownNow()
        Thread.currentThread().interrupt()
    } finally {
      super.afterAll()
    }
  }

  for {
    longClasspath <- List(false, true)
    debug <- List(false, true)
  } {
    val classpathName = if (longClasspath) "long" else "short"
    val mode = if (debug) "debug" else "run"
    test(s"$classpathName-classpath-$mode") {
      val root = AbsolutePath(Paths.get(System.getProperty("user.dir")))
      val classes = Paths.get(
        classOf[
          RunMainFixture
        ].getProtectionDomain.getCodeSource.getLocation.toURI
      )
      val copies =
        if (longClasspath)
          SystemProcess.processCmdCharLimit / classes.toString.length + 2
        else 1
      val classpath = List.fill(copies)(classes)
      assertEquals(
        classpath.mkString(File.pathSeparator).length >
          SystemProcess.processCmdCharLimit,
        longClasspath,
      )
      val listening = Promise[Option[InetSocketAddress]]()
      val output = new StringBuffer()
      val listener = new DebuggeeListener {
        override def onListening(address: InetSocketAddress): Unit =
          listening.trySuccess(Some(address))

        override def out(line: String): Unit =
          output.append(line).append("\n")

        override def err(line: String): Unit =
          output.append(line).append("\n")

        override def testResult(data: TestSuiteSummary): Unit = ()
      }
      val process = Run.runMain(
        root,
        classpath,
        Some(System.getProperty("java.home")),
        classOf[RunMainFixture].getName,
        List("first", "second"),
        List("-Drun-main-test=property"),
        List("RUN_MAIN_TEST=environment"),
        new Logger(listener),
        debug,
      )
      process.future.onComplete {
        case scala.util.Success(_) => listening.trySuccess(None)
        case scala.util.Failure(error) => listening.tryFailure(error)
      }
      val result =
        if (debug)
          listening.future.map { address =>
            assert(
              address.exists(_.getPort > 0),
              s"Missing JDWP listener; output: $output",
            )
          }
        else
          process.future.map { _ =>
            assertNoDiff(output.toString, "property|first,second|environment\n")
          }
      result.andThen { case _ => process.cancel() }
    }
  }
}
