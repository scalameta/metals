package scala.meta.internal.metals.mbt

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.SAXParserFactory

import scala.collection.mutable
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal
import scala.xml.Node
import scala.xml.XML

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.TestResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
    testCases.foreach { testCase =>
      val json = new JsonObject()
      json.addProperty("suiteName", testCase.suiteName)
      json.addProperty("testName", testCase.testName)
      json.addProperty("status", testCase.status.value)
      json.addProperty("duration", testCase.duration)
      testCase.error.foreach(json.addProperty("error", _))
      testCase.stackTrace.foreach(json.addProperty("stackTrace", _))
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
          val testCase = element.getAsJsonObject
          for {
            status <- MbtTestCaseStatus.fromString(
              testCase.get("status").getAsString
            )
          } yield MbtTestCaseResult(
            suiteName = testCase.get("suiteName").getAsString,
            testName = testCase.get("testName").getAsString,
            status = status,
            duration = testCase.get("duration").getAsLong,
            error = Option(testCase.get("error")).map(_.getAsString),
            stackTrace = Option(testCase.get("stackTrace")).map(_.getAsString),
          )
        }
        .toList
      MbtTestReport(cases)
    }.toOption
}

final case class MbtTestCommand(
    arguments: List[String],
    reportProvider: MbtTestReportProvider,
)

final case class MbtTestRunResult(
    exitCode: Int,
    report: MbtTestReport,
)

trait MbtTestReportProvider {
  def read(): MbtTestReport
}

object MbtTestReportProvider {
  val empty: MbtTestReportProvider = () => MbtTestReport.empty

  def changedJunitXmlDirectories(
      directories: List[AbsolutePath]
  ): MbtTestReportProvider = {
    val initialReports = xmlReports(directories).flatMap { report =>
      Try(MD5.compute(report.toNIO)).toOption.map(report -> _)
    }.toMap
    () =>
      try {
        val reports = xmlReports(directories).filter { report =>
          initialReports.get(report).forall(_ != MD5.compute(report.toNIO))
        }
        MbtTestReport(readReports(reports))
      } catch {
        case NonFatal(error) =>
          scribe.warn("Unable to read changed MBT test reports", error)
          MbtTestReport.empty
      }
  }

  def junitXmlDirectory(directory: AbsolutePath): MbtTestReportProvider =
    () =>
      try {
        val reports =
          xmlReports(List(directory))
        MbtTestReport(readReports(reports))
      } catch {
        case NonFatal(error) =>
          scribe.warn(s"Unable to read MBT test reports from $directory", error)
          MbtTestReport.empty
      } finally {
        Try(if (directory.exists) directory.deleteRecursively()).failed
          .foreach { error =>
            scribe.warn(s"Unable to remove MBT test reports $directory", error)
          }
      }

  def bazelBuildEvent(eventFile: AbsolutePath): MbtTestReportProvider =
    () =>
      try MbtTestReport(readReports(bazelTestXmlFiles(eventFile)))
      catch {
        case NonFatal(error) =>
          scribe.warn(
            s"Unable to read Bazel build events from $eventFile",
            error,
          )
          MbtTestReport.empty
      } finally {
        Try(eventFile.deleteIfExists()).failed.foreach { error =>
          scribe.warn(s"Unable to remove Bazel build events $eventFile", error)
        }
      }

  private[mbt] def readJunitXml(report: AbsolutePath): List[MbtTestCaseResult] =
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
        scribe.warn(s"Unable to read MBT test report $report", error)
        Nil
    }

  private def readTestCase(
      suite: Node,
      testCase: Node,
  ): MbtTestCaseResult = {
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

  private def xmlReports(directories: List[AbsolutePath]): List[AbsolutePath] =
    directories.flatMap { directory =>
      if (directory.isDirectory)
        directory.listRecursive.filter(_.extension == "xml").toList
      else Nil
    }

  private def readReports(
      reports: List[AbsolutePath]
  ): List[MbtTestCaseResult] = {
    val testCases = mutable.LinkedHashMap.empty[
      (String, String),
      MbtTestCaseResult,
    ]
    reports.flatMap(readJunitXml).foreach { testCase =>
      testCases.update((testCase.suiteName, testCase.testName), testCase)
    }
    testCases.values.toList
  }

  private def bazelTestXmlFiles(eventFile: AbsolutePath): List[AbsolutePath] = {
    if (!eventFile.isFile) Nil
    else {
      val reports = mutable.LinkedHashSet.empty[AbsolutePath]
      Using.resource(Files.lines(eventFile.toNIO)) { lines =>
        lines.forEach { line =>
          Try(JsonParser.parseString(line).getAsJsonObject).toOption
            .flatMap(json => Option(json.getAsJsonObject("testResult")))
            .flatMap(json => Option(json.getAsJsonArray("testActionOutput")))
            .foreach { outputs =>
              List.tabulate(outputs.size)(outputs.get).foreach { output =>
                val file = output.getAsJsonObject
                val name = Option(file.get("name")).map(_.getAsString)
                val uri = Option(file.get("uri")).map(_.getAsString)
                if (name.exists(_.endsWith("test.xml"))) {
                  uri.flatMap(fileUri).filter(_.isFile).foreach(reports.add)
                }
              }
            }
        }
      }
      reports.toList
    }
  }

  private def fileUri(value: String): Option[AbsolutePath] =
    Try(URI.create(value)).toOption
      .filter(uri => uri.getScheme == "file")
      .flatMap(uri => Try(AbsolutePath(Paths.get(uri))).toOption)
}
