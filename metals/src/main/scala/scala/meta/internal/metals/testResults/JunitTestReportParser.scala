package scala.meta.internal.metals.testResults

import javax.xml.parsers.SAXParserFactory

import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonFatal
import scala.xml.Node
import scala.xml.XML

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Parses JUnit-compatible XML test reports into [[TestReport]].
 *
 * This is a shared, build-tool-agnostic parser. Each build tool is responsible
 * for discovering which XML files to pass here; this object only converts
 * raw XML into the [[TestReport]] model.
 */
object JunitTestReportParser {

  /**
   * Parses a single JUnit XML report file into a list of test-case results.
   * Returns an empty list if the file cannot be read or parsed.
   */
  def parse(report: AbsolutePath): List[TestCaseResult] =
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
        (suite \ "testcase").map(parseTestCase(suite, _))
      }
    } catch {
      case NonFatal(error) =>
        scribe.warn(s"Unable to read test report $report", error)
        Nil
    }

  /**
   * Merges results from multiple XML report files, deduplicating by
   * `(suiteName, testName)` so that re-runs overwrite earlier entries.
   */
  def merge(reports: List[AbsolutePath]): TestReport = {
    val testCases =
      mutable.LinkedHashMap.empty[(String, String), TestCaseResult]
    reports.flatMap(parse).foreach { tc =>
      testCases.update((tc.suiteName, tc.testName), tc)
    }
    TestReport(testCases.values.toList)
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

  private def parseTestCase(suite: Node, testCase: Node): TestCaseResult = {
    val failure =
      (testCase \ "failure").headOption.orElse((testCase \ "error").headOption)
    val skipped = (testCase \ "skipped").nonEmpty
    val status =
      if (failure.nonEmpty) TestCaseStatus.Failed
      else if (skipped) TestCaseStatus.Skipped
      else TestCaseStatus.Passed
    val stackTrace = failure.map(_.text.trim).filter(_.nonEmpty)
    val error = failure
      .flatMap(node =>
        attribute(node, "message").orElse(attribute(node, "type"))
      )
      .orElse(stackTrace.flatMap(_.linesIterator.nextOption()))
    TestCaseResult(
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
