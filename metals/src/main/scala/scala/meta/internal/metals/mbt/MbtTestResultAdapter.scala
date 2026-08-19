package scala.meta.internal.metals.mbt

import java.io.Closeable

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.TestSuitesProvider

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
import ch.epfl.scala.bsp4j.ScalaTestSuites
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.ClassEntry
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
    testProvider: TestSuitesProvider,
    targetId: BuildTargetIdentifier,
)(implicit ec: ExecutionContext)
    extends Debuggee {

  override def name: String = inner.name
  override def scalaVersion: ScalaVersion = inner.scalaVersion
  override def modules: Seq[Module] = inner.modules
  override def libraries: Seq[Library] = inner.libraries
  override def unmanagedEntries: Seq[UnmanagedEntry] = inner.unmanagedEntries
  override def javaRuntime: Option[JavaRuntime] = inner.javaRuntime
  override def classEntries: Seq[ClassEntry] = inner.classEntries
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
  ): Unit =
    MbtTestResultAdapter
      .testSuiteSummaries(
        testSuites.getSuites.asScala.toList,
        testProvider,
        targetId,
        passed,
        duration,
      )
      .foreach(listener.testResult)
}

object MbtTestResultAdapter {

  /**
   * Wraps an existing Debuggee to add test result reporting for MBT.
   */
  def apply(
      inner: Debuggee,
      testSuites: ScalaTestSuites,
      testProvider: TestSuitesProvider,
      targetId: BuildTargetIdentifier,
  )(implicit ec: ExecutionContext): MbtTestResultAdapter =
    new MbtTestResultAdapter(inner, testSuites, testProvider, targetId)

  /**
   * Builds one [[TestSuiteSummary]] per requested suite. Every
   * name we can attribute to this run gets the same pass/fail verdict.
   */
  def testSuiteSummaries(
      suites: List[ScalaTestSuiteSelection],
      testProvider: TestSuitesProvider,
      targetId: BuildTargetIdentifier,
      passed: Boolean,
      duration: Long,
  ): List[TestSuiteSummary] =
    suites.map { suite =>
      val className = suite.getClassName
      val selectedTests = suite.getTests.asScala.toList
      val testNames =
        if (selectedTests.nonEmpty) selectedTests
        else
          // If the whole suit is selected, we still need to send data about all test cases
          // added to the client via `AddTestCases` for the results to show up correctly
          testProvider.knownTestCaseNames(targetId, className)

      val testResults: java.util.List[SingleTestSummary] =
        if (testNames.isEmpty) {
          java.util.Collections.singletonList(
            singleTestResult(className, passed, "Test suite failed", duration)
          )
        } else {
          testNames
            .map(testName =>
              singleTestResult(
                s"$className.$testName",
                passed,
                "Test failed",
                duration,
              )
            )
            .asJava
        }

      TestSuiteSummary(className, duration, testResults)
    }

  private def singleTestResult(
      testName: String,
      passed: Boolean,
      message: String,
      duration: Long,
  ): SingleTestSummary =
    if (passed) SingleTestResult.Passed(testName, duration)
    else
      SingleTestResult.Failed(
        testName,
        duration,
        message,
        null,
        null,
      )
}
