package tests.mbt

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtTestCaseResult
import scala.meta.internal.metals.mbt.MbtTestCaseStatus
import scala.meta.internal.metals.mbt.MbtTestReport
import scala.meta.internal.metals.mbt.MbtTestReportProvider
import scala.meta.internal.metals.mbt.MbtTestResultAdapter
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.debugadapter.testing.SingleTestResult

class MbtTestReportSuite extends munit.FunSuite {

  test("read-junit-xml") {
    val directory = AbsolutePath(Files.createTempDirectory("mbt-test-report"))
    directory.resolve("TEST-example.FooSuite.xml").writeText(junitReport)

    val report = MbtTestReportProvider.junitXmlDirectory(directory).read()

    assertEquals(
      report.testCases.map(test => (test.testName, test.status, test.duration)),
      List(
        ("passes", MbtTestCaseStatus.Passed, 12L),
        ("fails", MbtTestCaseStatus.Failed, 34L),
        ("skips", MbtTestCaseStatus.Skipped, 0L),
      ),
    )
    assertEquals(report.testCases(1).error, Some("expected true"))
    assertEquals(report.testCases(1).stackTrace, Some("example stack trace"))
  }

  test("read-bazel-build-event") {
    val directory = AbsolutePath(Files.createTempDirectory("mbt-bazel-report"))
    val xml = directory.resolve("test.xml")
    xml.writeText(junitReport)
    val eventFile = directory.resolve("build-events.json")
    eventFile.writeText(
      s"""{"testResult":{"testActionOutput":[{"name":"test.log","uri":"${xml.toURI}"},{"name":"test.xml","uri":"${xml.toURI}"}]}}"""
    )

    val report = MbtTestReportProvider.bazelBuildEvent(eventFile).read()

    assertEquals(
      report.testCases.map(_.testName),
      List("passes", "fails", "skips"),
    )
    assert(!eventFile.exists)
  }

  test("report-json-roundtrip") {
    val report = MbtTestReport(
      List(
        MbtTestCaseResult(
          "example.FooSuite",
          "fails",
          MbtTestCaseStatus.Failed,
          34L,
          Some("expected true"),
          Some("example stack trace"),
        )
      )
    )
    val result = new TestResult(ch.epfl.scala.bsp4j.StatusCode.ERROR)
    result.setDataKind(MbtTestReport.dataKind)
    result.setData(report.toJson)

    assertEquals(MbtTestReport.fromTestResult(result), Some(report))
  }

  test("use-individual-results") {
    val report = MbtTestReport(
      List(
        MbtTestCaseResult(
          "example.FooSuite",
          "passes",
          MbtTestCaseStatus.Passed,
          12L,
          None,
          None,
        ),
        MbtTestCaseResult(
          "example.FooSuite",
          "fails",
          MbtTestCaseStatus.Failed,
          34L,
          Some("expected true"),
          Some("example stack trace"),
        ),
      )
    )
    val suites = List(
      new ScalaTestSuiteSelection(
        "example.FooSuite",
        List("passes()", "fails").asJava,
      )
    )

    val summaries = MbtTestResultAdapter.testSuiteSummaries(
      suites,
      null,
      new BuildTargetIdentifier("mbt://example"),
      passed = false,
      duration = 100L,
      report = Some(report),
    )
    val tests = summaries.head.tests.asScala.toList

    assertEquals(summaries.head.duration, 46L)
    assertEquals(tests.size, 2)
    tests.head match {
      case passed: SingleTestResult.Passed =>
        assertEquals(passed.testName, "example.FooSuite.passes()")
      case result => fail(s"Expected passed result, obtained $result")
    }
    tests(1) match {
      case failed: SingleTestResult.Failed =>
        assertEquals(failed.testName, "example.FooSuite.fails")
        assertEquals(failed.error, "expected true")
        assertEquals(failed.stackTrace, "example stack trace")
      case result => fail(s"Expected failed result, obtained $result")
    }
  }

  private val junitReport =
    """|<?xml version="1.0" encoding="UTF-8"?>
       |<testsuites>
       |  <testsuite name="example.FooSuite" tests="3" failures="1" skipped="1">
       |    <testcase classname="example.FooSuite" name="passes" time="0.012" />
       |    <testcase classname="example.FooSuite" name="fails" time="0.034">
       |      <failure message="expected true" type="java.lang.AssertionError">example stack trace</failure>
       |    </testcase>
       |    <testcase classname="example.FooSuite" name="skips" time="0">
       |      <skipped />
       |    </testcase>
       |  </testsuite>
       |</testsuites>
       |""".stripMargin
}
