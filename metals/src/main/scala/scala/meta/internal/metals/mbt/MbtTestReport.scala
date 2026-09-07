package scala.meta.internal.metals.mbt

import scala.util.Try

import scala.meta.internal.metals.testResults.TestCaseResult
import scala.meta.internal.metals.testResults.TestCaseStatus
import scala.meta.internal.metals.testResults.TestReport

import ch.epfl.scala.bsp4j.TestResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * BSP codec for the MBT-specific `buildTarget/test` data payload.
 *
 * The `dataKind = "metals-mbt-test-report"` transport is an MBT
 * implementation detail.  The generic model ([[TestReport]] and friends) lives
 * in the `testResults` package; this object is the only place that knows about
 * the BSP wire format.
 */
object MbtTestReport {

  val dataKind: String = "metals-mbt-test-report"

  def toJson(report: TestReport): JsonElement = {
    val cases = new JsonArray()
    report.testCases.foreach { tc =>
      val json = new JsonObject()
      json.addProperty("suiteName", tc.suiteName)
      json.addProperty("testName", tc.testName)
      json.addProperty("status", tc.status.value)
      json.addProperty("duration", tc.duration)
      tc.error.foreach(json.addProperty("error", _))
      tc.stackTrace.foreach(json.addProperty("stackTrace", _))
      cases.add(json)
    }
    val report2 = new JsonObject()
    report2.add("testCases", cases)
    report2
  }

  def fromTestResult(result: TestResult): Option[TestReport] =
    Option(result.getDataKind)
      .filter(_ == dataKind)
      .flatMap(_ => Option(result.getData))
      .collect { case json: JsonElement => json }
      .flatMap(fromJson)

  def fromJson(json: JsonElement): Option[TestReport] =
    Try {
      val jsonCases = json.getAsJsonObject
        .getAsJsonArray("testCases")
      val cases = List
        .tabulate(jsonCases.size)(jsonCases.get)
        .flatMap { element =>
          val tc = element.getAsJsonObject
          for {
            status <- TestCaseStatus.fromString(
              tc.get("status").getAsString
            )
          } yield TestCaseResult(
            suiteName = tc.get("suiteName").getAsString,
            testName = tc.get("testName").getAsString,
            status = status,
            duration = tc.get("duration").getAsLong,
            error = Option(tc.get("error")).map(_.getAsString),
            stackTrace = Option(tc.get("stackTrace")).map(_.getAsString),
          )
        }
      TestReport(cases)
    }.toOption
}
