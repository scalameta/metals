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

sealed abstract class MbtTestCaseStatus(val value: String)

object MbtTestCaseStatus {

  case object Passed extends MbtTestCaseStatus("passed")
  case object Failed extends MbtTestCaseStatus("failed")
  case object Skipped extends MbtTestCaseStatus("skipped")

  def fromString(value: String): Option[MbtTestCaseStatus] =
    List(Passed, Failed, Skipped).find(_.value == value)
}

final case class MbtTestCaseResult(
    suiteName: String,
    testName: String,
    status: MbtTestCaseStatus,
    duration: Long,
    error: Option[String] = None,
    stackTrace: Option[String] = None,
)

final case class MbtTestReport(testCases: List[MbtTestCaseResult]) {
  def toJson: JsonElement = {
    val result = new JsonArray()
    testCases.foreach { testCase =>
      val json = new JsonObject()
      json.addProperty("suiteName", testCase.suiteName)
      json.addProperty("testName", testCase.testName)
      json.addProperty("status", testCase.status.value)
      json.addProperty("duration", testCase.duration)
      testCase.error.foreach(json.addProperty("error", _))
      testCase.stackTrace.foreach(json.addProperty("stackTrace", _))
      result.add(json)
    }
    result
  }
}

object MbtTestReport {

  val dataKind: String = "metals-mbt-test-report"
  val empty: MbtTestReport = MbtTestReport(Nil)

  def fromTestResult(result: TestResult): Option[MbtTestReport] =
    for {
      _ <- Option(result.getDataKind).filter(_ == dataKind)
      json <- Option(result.getData).collect { case json: JsonElement => json }
      testCases <- Try {
        val cases = json.getAsJsonArray
        List.tabulate(cases.size)(cases.get).flatMap { element =>
          val testCase = element.getAsJsonObject
          MbtTestCaseStatus
            .fromString(testCase.get("status").getAsString)
            .map { status =>
              MbtTestCaseResult(
                suiteName = testCase.get("suiteName").getAsString,
                testName = testCase.get("testName").getAsString,
                status = status,
                duration = testCase.get("duration").getAsLong,
                error = Option(testCase.get("error")).map(_.getAsString),
                stackTrace = Option(testCase.get("stackTrace"))
                  .map(_.getAsString),
              )
            }
        }
      }.toOption
    } yield MbtTestReport(testCases)

  /** Parses a single JUnit XML report file into a list of test-case results. */
  private def parseJunitXml(report: AbsolutePath): List[MbtTestCaseResult] =
    try {
      val factory = SAXParserFactory.newInstance()
      factory.setFeature(
        "http://apache.org/xml/features/disallow-doctype-decl",
        true,
      )
      List(
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
      ).foreach(factory.setFeature(_, false))
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

  def readJunitReports(
      reports: => List[AbsolutePath],
      description: String,
      cleanup: => Unit = (),
  ): MbtTestReport =
    try mergeJunitXml(reports)
    catch {
      case NonFatal(error) =>
        scribe.warn(s"Unable to read $description", error)
        empty
    } finally {
      Try(cleanup).failed.foreach { error =>
        scribe.warn(s"Unable to clean up $description", error)
      }
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

/** MBT test command with its associated report provider. */
final case class MbtTestCommand(
    arguments: List[String],
    consumeReport: () => MbtTestReport = () => MbtTestReport.empty,
)

/** Outcome of an MBT test run: process exit code and the parsed report. */
final case class MbtTestRunResult(
    exitCode: Int,
    report: MbtTestReport,
)
