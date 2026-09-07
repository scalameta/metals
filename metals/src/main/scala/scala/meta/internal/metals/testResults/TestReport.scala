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
