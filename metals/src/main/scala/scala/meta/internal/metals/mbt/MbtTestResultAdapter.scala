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
 * Test case results are read from build-tool reports when available. If the
 * build tool does not provide a report, results fall back to the process exit
 * code.
 */
class MbtTestResultAdapter(
    inner: Debuggee,
    testSuites: ScalaTestSuites,
    testProvider: TestSuitesProvider,
    targetId: BuildTargetIdentifier,
    report: () => Option[MbtTestReport] = () => None,
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
        report(),
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
      report: () => Option[MbtTestReport] = () => None,
  )(implicit ec: ExecutionContext): MbtTestResultAdapter =
    new MbtTestResultAdapter(
      inner,
      testSuites,
      testProvider,
      targetId,
      report,
    )

  /** Builds one [[TestSuiteSummary]] per requested suite. */
  def testSuiteSummaries(
      suites: List[ScalaTestSuiteSelection],
      testProvider: TestSuitesProvider,
      targetId: BuildTargetIdentifier,
      passed: Boolean,
      duration: Long,
      report: Option[MbtTestReport] = None,
  ): List[TestSuiteSummary] =
    suites.map { suite =>
      val className = suite.getClassName
      val selectedTests = suite.getTests.asScala.toList
      lazy val testNames =
        if (selectedTests.nonEmpty) selectedTests
        else
          // If the whole suit is selected, we still need to send data about all test cases
          // added to the client via `AddTestCases` for the results to show up correctly
          testProvider.knownTestCaseNames(targetId, className)

      val reportedTests = report.toList
        .flatMap(_.testCases)
        .filter(test => test.suiteName == className)

      val testResults: java.util.List[SingleTestSummary] =
        if (reportedTests.nonEmpty) {
          reportedTests
            .map(toSingleTestSummary(className, testNames, _))
            .asJava
        } else if (testNames.isEmpty) {
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

      val suiteDuration =
        if (reportedTests.nonEmpty) reportedTests.map(_.duration).sum
        else duration
      TestSuiteSummary(className, suiteDuration, testResults)
    }

  private def toSingleTestSummary(
      className: String,
      knownTestNames: List[String],
      test: MbtTestCaseResult,
  ): SingleTestSummary = {
    val reportedName = knownTestNames
      .find(_ == test.testName)
      .orElse(
        knownTestNames.find(_.stripSuffix("()") == test.testName)
      )
      .getOrElse(test.testName)
    val testName = s"$className.$reportedName"
    test.status match {
      case MbtTestCaseStatus.Passed =>
        SingleTestResult.Passed(testName, test.duration)
      case MbtTestCaseStatus.Skipped =>
        SingleTestResult.Skipped(testName)
      case MbtTestCaseStatus.Failed =>
        SingleTestResult.Failed(
          testName,
          test.duration,
          test.error.getOrElse("Test failed"),
          test.stackTrace.orNull,
          null,
        )
    }
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
