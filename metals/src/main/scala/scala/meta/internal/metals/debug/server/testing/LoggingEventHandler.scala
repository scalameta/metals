package scala.meta.internal.metals.debug.server.testing

import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.testing.TestSuiteEvent
import ch.epfl.scala.debugadapter.testing.TestSuiteEventHandler
import ch.epfl.scala.debugadapter.testing.TestUtils
import sbt.testing.Event
import sbt.testing.Status

class LoggingEventHandler(listener: DebuggeeListener)
    extends TestSuiteEventHandler {
  type SuiteName = String
  type TestName = String
  type FailureMessage = String

  private val failedStatuses =
    Set(Status.Error, Status.Canceled, Status.Failure)

  protected var suitesDuration = 0L
  protected var suitesPassed = 0
  protected var suitesAborted = 0
  protected val testsFailedBySuite
      : mutable.SortedMap[SuiteName, Map[TestName, FailureMessage]] =
    mutable.SortedMap.empty[SuiteName, Map[TestName, FailureMessage]]
  protected var suitesTotal = 0

  protected def formatMetrics(metrics: List[(Int, String)]): String = {
    val relevant = metrics.iterator.filter(_._1 > 0)
    relevant.map { case (value, metric) => value + " " + metric }.mkString(", ")
  }

  override def handle(event: TestSuiteEvent): Unit = event match {
    case TestSuiteEvent.Error(message) =>
      listener.err(message)
      error(message)
    case TestSuiteEvent.Warn(message) => scribe.warn(message)
    case TestSuiteEvent.Info(message) => info(message)
    case TestSuiteEvent.Debug(message) => scribe.debug(message)
    case TestSuiteEvent.Trace(throwable) =>
      error("Test suite aborted")
      scribe.trace(throwable)
      suitesAborted += 1
      suitesTotal += 1

    case results @ TestSuiteEvent.Results(testSuite, events) =>
      val summary = TestSuiteEventHandler.summarizeResults(results)
      listener.testResult(summary)
      val testsTotal = events.length

      info(
        s"Execution took ${TimeFormat.readableMillis(results.duration)}"
      )
      val regularMetrics = List(
        testsTotal -> "tests",
        results.passed -> "passed",
        results.pending -> "pending",
        results.ignored -> "ignored",
        results.skipped -> "skipped",
      )

      // If test metrics
      val failureCount = results.failed + results.canceled + results.errors
      val failureMetrics =
        List(
          results.failed -> "failed",
          results.canceled -> "canceled",
          results.errors -> "errors",
        )
      val testMetrics = formatMetrics(regularMetrics ++ failureMetrics)
      if (!testMetrics.isEmpty) info(testMetrics)

      if (failureCount > 0) {
        val currentFailedTests = extractErrors(events)
        val previousFailedTests =
          testsFailedBySuite.getOrElse(testSuite, Map.empty)
        testsFailedBySuite += testSuite -> (previousFailedTests ++ currentFailedTests)
      } else if (testsTotal <= 0) info("No test suite was run")
      else {
        suitesPassed += 1
        info(s"All tests in $testSuite passed")
      }

      suitesTotal += 1
      suitesDuration += results.duration

    case TestSuiteEvent.Done => ()
  }

  private def extractErrors(events: List[Event]) =
    events
      .filter(e => failedStatuses.contains(e.status()))
      .map { event =>
        val selectorOpt = TestUtils.printSelector(event.selector)
        if (selectorOpt.isEmpty) {
          scribe.debug(
            s"Unexpected test selector ${event.selector} won't be pretty printed!"
          )
        }
        val key = selectorOpt.getOrElse("")
        val value = TestUtils.printThrowable(event.throwable()).getOrElse("")
        key -> value
      }
      .toMap

  def report(): Unit = {
    // TODO: Shall we think of a better way to format this delimiter based on screen length?
    info("===============================================")
    info(s"Total duration: ${TimeFormat.readableMillis(suitesDuration)}")

    if (suitesTotal == 0) {
      info(s"No test suites were run.")
    } else if (suitesPassed == suitesTotal) {
      info(s"All $suitesPassed test suites passed.")
    } else {
      val metrics = List(
        suitesPassed -> "passed",
        testsFailedBySuite.size -> "failed",
        suitesAborted -> "aborted",
      )

      info(formatMetrics(metrics))
      if (testsFailedBySuite.nonEmpty) {
        info("")
        info("Failed:")
        testsFailedBySuite.foreach { case (suiteName, failedTests) =>
          info(s"- $suiteName:")
          val summary = failedTests.map { case (suiteName, failureMsg) =>
            TestSuiteEventHandler.formatError(
              suiteName,
              failureMsg,
              indentSize = 2,
            )
          }
          summary.foreach(s => info(s))
        }
      }
    }

    info("===============================================")
  }

  def info(message: String): Unit = {
    listener.out(message)
    scribe.info(message)
  }

  def error(message: String): Unit = {
    listener.err(message)
    scribe.error(message)
  }
}

object TimeFormat {
  def readableMillis(nanos: Long): String = {
    import java.text.DecimalFormat
    import java.text.DecimalFormatSymbols
    val seconds = TimeUnit.MILLISECONDS.toSeconds(nanos)
    if (seconds > 9) readableSeconds(seconds)
    else {
      val ms = TimeUnit.MILLISECONDS.toMillis(nanos)
      if (ms < 100) {
        s"${ms}ms"
      } else {
        val partialSeconds = ms.toDouble / 1000
        new DecimalFormat("#.##s", new DecimalFormatSymbols(Locale.US))
          .format(partialSeconds)
      }
    }
  }

  def readableSeconds(n: Long): String = {
    val minutes = n / 60
    val seconds = n % 60
    if (minutes > 0) {
      if (seconds == 0) s"${minutes}m"
      else s"${minutes}m${seconds}s"
    } else {
      s"${seconds}s"
    }
  }
}
