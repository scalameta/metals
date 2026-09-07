package scala.meta.internal.metals.testResults

/** Normalised result for a single test case, independent of build tool. */
final case class TestCaseResult(
    suiteName: String,
    testName: String,
    status: TestCaseStatus,
    duration: Long,
    error: Option[String],
    stackTrace: Option[String],
)

/** Aggregated test report produced by a build tool test run. */
final case class TestReport(testCases: List[TestCaseResult])

object TestReport {
  val empty: TestReport = TestReport(Nil)
}

/**
 * Abstraction over build-tool-specific report discovery.
 *
 * Each build tool constructs an instance that knows how to locate and read the
 * XML (or other) artefacts left behind by a test run.
 */
trait TestReportProvider {
  def read(): TestReport
}

object TestReportProvider {
  val empty: TestReportProvider = () => TestReport.empty
}

/** Command produced by a build tool together with its report provider. */
final case class TestCommand(
    arguments: List[String],
    reportProvider: TestReportProvider,
)

/** Outcome of a test run: process exit code and the parsed report. */
final case class TestRunResult(
    exitCode: Int,
    report: TestReport,
)
