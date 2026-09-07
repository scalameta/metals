package scala.meta.internal.metals.mbt

import javax.xml.parsers.SAXParserFactory

import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonFatal
import scala.xml.Node
import scala.xml.XML

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.TestResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

sealed trait MbtTestCaseStatus {
  def value: String
}

object MbtTestCaseStatus {

  case object Passed extends MbtTestCaseStatus {
    override val value: String = "passed"
  }
  case object Failed extends MbtTestCaseStatus {
    override val value: String = "failed"
  }
  case object Skipped extends MbtTestCaseStatus {
    override val value: String = "skipped"
  }

  def fromString(value: String): Option[MbtTestCaseStatus] =
    value match {
      case Passed.value => Some(Passed)
      case Failed.value => Some(Failed)
      case Skipped.value => Some(Skipped)
      case _ => None
    }
}

final case class MbtTestCaseResult(
    suiteName: String,
    testName: String,
    status: MbtTestCaseStatus,
    duration: Long,
    error: Option[String],
    stackTrace: Option[String],
)

final case class MbtTestReport(testCases: List[MbtTestCaseResult]) {
  def toJson: JsonElement = {
    val cases = new JsonArray()
    testCases.foreach { tc =>
      val json = new JsonObject()
      json.addProperty("suiteName", tc.suiteName)
      json.addProperty("testName", tc.testName)
      json.addProperty("status", tc.status.value)
      json.addProperty("duration", tc.duration)
      tc.error.foreach(json.addProperty("error", _))
      tc.stackTrace.foreach(json.addProperty("stackTrace", _))
      cases.add(json)
    }
    val report = new JsonObject()
    report.add("testCases", cases)
    report
  }
}

object MbtTestReport {

  val dataKind: String = "metals-mbt-test-report"
  val empty: MbtTestReport = MbtTestReport(Nil)

  def fromTestResult(result: TestResult): Option[MbtTestReport] =
    Option(result.getDataKind)
      .filter(_ == dataKind)
      .flatMap(_ => Option(result.getData))
      .collect { case json: JsonElement => json }
      .flatMap(fromJson)

  def fromJson(json: JsonElement): Option[MbtTestReport] =
    Try {
      val jsonCases = json.getAsJsonObject
        .getAsJsonArray("testCases")
      val cases = List
        .tabulate(jsonCases.size)(jsonCases.get)
        .flatMap { element =>
          val tc = element.getAsJsonObject
          for {
            status <- MbtTestCaseStatus.fromString(
              tc.get("status").getAsString
            )
          } yield MbtTestCaseResult(
            suiteName = tc.get("suiteName").getAsString,
            testName = tc.get("testName").getAsString,
            status = status,
            duration = tc.get("duration").getAsLong,
            error = Option(tc.get("error")).map(_.getAsString),
            stackTrace = Option(tc.get("stackTrace")).map(_.getAsString),
          )
        }
      MbtTestReport(cases)
    }.toOption

  /** Parses a single JUnit XML report file into a list of test-case results. */
  def parseJunitXml(report: AbsolutePath): List[MbtTestCaseResult] =
    try {
      val factory = SAXParserFactory.newInstance()
      factory.setFeature(
        "http://apache.org/xml/features/disallow-doctype-decl",
        true,
      )
      factory.setFeature(
        "http://xml.org/sax/features/external-general-entities",
        false,
      )
      factory.setFeature(
        "http://xml.org/sax/features/external-parameter-entities",
        false,
      )
      factory.setFeature(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        false,
      )
      factory.setXIncludeAware(false)
      val xml =
        XML.withSAXParser(factory.newSAXParser()).loadFile(report.toFile)
      val suites =
        if (xml.label == "testsuite") List(xml)
        else (xml \\ "testsuite").toList
      suites.flatMap { suite =>
        (suite \ "testcase").map(readTestCase(suite, _))
      }
    } catch {
      case NonFatal(error) =>
        scribe.warn(s"Unable to read test report $report", error)
        Nil
    }

  /**
   * Merges results from multiple JUnit XML report files, deduplicating by
   * `(suiteName, testName)` so that re-runs overwrite earlier entries.
   */
  def mergeJunitXml(reports: List[AbsolutePath]): MbtTestReport = {
    val testCases =
      mutable.LinkedHashMap.empty[(String, String), MbtTestCaseResult]
    reports.flatMap(parseJunitXml).foreach { tc =>
      testCases.update((tc.suiteName, tc.testName), tc)
    }
    MbtTestReport(testCases.values.toList)
  }

  /**
   * Recursively finds all `.xml` files inside the given directories.
   * Directories that do not exist are silently skipped.
   */
  def xmlFiles(directories: List[AbsolutePath]): List[AbsolutePath] =
    directories.flatMap { directory =>
      if (directory.isDirectory)
        directory.listRecursive.filter(_.extension == "xml").toList
      else Nil
    }

  private def readTestCase(suite: Node, testCase: Node): MbtTestCaseResult = {
    val failure =
      (testCase \ "failure").headOption.orElse((testCase \ "error").headOption)
    val skipped = (testCase \ "skipped").nonEmpty
    val status =
      if (failure.nonEmpty) MbtTestCaseStatus.Failed
      else if (skipped) MbtTestCaseStatus.Skipped
      else MbtTestCaseStatus.Passed
    val stackTrace = failure.map(_.text.trim).filter(_.nonEmpty)
    val error = failure
      .flatMap(node =>
        attribute(node, "message").orElse(attribute(node, "type"))
      )
      .orElse(stackTrace.flatMap(_.linesIterator.nextOption()))
    MbtTestCaseResult(
      suiteName = attribute(testCase, "classname")
        .orElse(attribute(suite, "name"))
        .getOrElse(""),
      testName = attribute(testCase, "name").getOrElse(""),
      status = status,
      duration = attribute(testCase, "time")
        .flatMap(value => Try((BigDecimal(value) * 1000).toLong).toOption)
        .getOrElse(0L),
      error = error,
      stackTrace = stackTrace,
    )
  }

  private def attribute(node: Node, name: String): Option[String] =
    node.attribute(name).map(_.text).filter(_.nonEmpty)
}

/** MBT-specific abstraction over build-tool report discovery. */
trait MbtTestReportProvider {
  def read(): MbtTestReport
}

object MbtTestReportProvider {
  val empty: MbtTestReportProvider = () => MbtTestReport.empty
}

/** MBT test command with its associated report provider. */
final case class MbtTestCommand(
    arguments: List[String],
    reportProvider: MbtTestReportProvider,
)

/** Outcome of an MBT test run: process exit code and the parsed report. */
final case class MbtTestRunResult(
    exitCode: Int,
    report: MbtTestReport,
)
