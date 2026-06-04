package scala.meta.internal.metals.mbt

import java.io.Closeable

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.ScalaTestSuites
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.Debuggee
import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.JavaRuntime
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.ScalaVersion
import ch.epfl.scala.debugadapter.UnmanagedEntry
import ch.epfl.scala.debugadapter.testing.SingleTestResult
import ch.epfl.scala.debugadapter.testing.SingleTestSummary
import ch.epfl.scala.debugadapter.testing.TestSuiteSummary

/**
 * Wrapper adapter for MBT test execution that intercepts test completion
 * and sends test result events to the debug client.
 *
 * Since MBT runs tests via external commands (like `bazel test`), we don't have
 * access to individual test case results. Instead, we report a summary based on
 * the exit code: if the process exits with 0, all tests are reported as passed;
 * otherwise, they are reported as failed.
 */
class MbtTestResultAdapter(
    inner: Debuggee,
    testSuites: ScalaTestSuites,
)(implicit ec: ExecutionContext)
    extends Debuggee {

  override def name: String = inner.name
  override def scalaVersion: ScalaVersion = inner.scalaVersion
  override def modules: Seq[Module] = inner.modules
  override def libraries: Seq[Library] = inner.libraries
  override def unmanagedEntries: Seq[UnmanagedEntry] = inner.unmanagedEntries
  override def javaRuntime: Option[JavaRuntime] = inner.javaRuntime
  override def observeClassUpdates(
      onClassUpdate: Seq[String] => Unit
  ): Closeable = inner.observeClassUpdates(onClassUpdate)

  /**
   * Runs the tests and sends test result events when complete.
   *
   * @param listener The debuggee listener to send events to
   * @return A CancelableFuture that completes when tests finish
   */
  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val startTime = System.currentTimeMillis()
    val innerFuture = inner.run(listener)

    new CancelableFuture[Unit] {
      def future: Future[Unit] = {
        innerFuture.future
          .map { _ =>
            val duration = System.currentTimeMillis() - startTime
            sendTestResults(listener, passed = true, duration)
          }
          .recoverWith { case ex =>
            val duration = System.currentTimeMillis() - startTime
            sendTestResults(listener, passed = false, duration)
            Future.failed(ex)
          }
      }

      def cancel(): Unit = innerFuture.cancel()
    }
  }

  private def sendTestResults(
      listener: DebuggeeListener,
      passed: Boolean,
      duration: Long,
  ): Unit = {
    val suites = testSuites.getSuites.asScala.toList

    for (suite <- suites) {
      val className = suite.getClassName
      val selectedTests = suite.getTests.asScala.toList

      val testResults: java.util.List[SingleTestSummary] =
        if (selectedTests.isEmpty) {
          if (passed)
            java.util.Collections.singletonList(
              SingleTestResult.Passed(className, duration)
            )
          else
            java.util.Collections.singletonList(
              SingleTestResult.Failed(
                className,
                duration,
                "Test suite failed",
                null,
                null,
              )
            )
        } else {
          val results = selectedTests.map { testName =>
            val fullTestName = s"$className.$testName"
            val result: SingleTestSummary =
              if (passed)
                SingleTestResult.Passed(fullTestName, duration)
              else
                SingleTestResult.Failed(
                  fullTestName,
                  duration,
                  "Test failed",
                  null,
                  null,
                )
            result
          }
          results.asJava
        }

      val summary = TestSuiteSummary(className, duration, testResults)
      listener.testResult(summary)
    }
  }
}

object MbtTestResultAdapter {

  /**
   * Wraps an existing Debuggee to add test result reporting for MBT.
   */
  def apply(
      inner: Debuggee,
      testSuites: ScalaTestSuites,
  )(implicit ec: ExecutionContext): MbtTestResultAdapter =
    new MbtTestResultAdapter(inner, testSuites)
}
