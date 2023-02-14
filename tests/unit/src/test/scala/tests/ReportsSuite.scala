package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.Report
import scala.meta.internal.metals.Reports
import scala.meta.io.AbsolutePath

class ReportsSuite extends BaseSuite {
  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))
  val reportsProvider = new Reports(workspace)

  def exampleText(workspaceStr: String = workspace.toString()): String =
    s"""|An error happend in the file:
        |${workspaceStr}/WrongFile.scala
        |""".stripMargin

  override def afterEach(context: AfterEach): Unit = {
    reportsProvider.deleteAll()
    super.afterEach(context)
  }

  test("create-report") {
    val path =
      reportsProvider.incognito.createReport("test_error", exampleText())
    val obtained =
      new String(Files.readAllBytes(path.toNIO), StandardCharsets.UTF_8)
    assertEquals(exampleText(Reports.WORKSPACE_STR), obtained)
    assert(Report.fromFile(path.toFile).nonEmpty)
  }

  test("delete-old-reports") {
    reportsProvider.incognito.createReport("some_test_error_old", exampleText())
    reportsProvider.incognito.createReport(
      "some_different_test_error_old",
      exampleText(),
    )
    reportsProvider.incognito.createReport("some_test_error_new", exampleText())
    reportsProvider.incognito.createReport(
      "some_different_test_error_new",
      exampleText(),
    )
    val deleted = reportsProvider.incognito.cleanUpOldReports(2)
    assertEquals(deleted.length, 2)
    deleted.foreach(f => assert(f.name.contains("old")))
    val reports = reportsProvider.incognito.getReports
    assertEquals(reports.length, 2)
    reports.foreach(f => assert(f.name.contains("new")))
  }

  test("zip-reports") {
    reportsProvider.incognito.createReport("test_error", exampleText())
    reportsProvider.incognito.createReport(
      "different_test_error",
      exampleText(),
    )
    val pathToZip = reportsProvider.zipReports()
    assertEquals(pathToZip.toFile.getName(), Reports.ZIP_FILE_NAME)
  }
}
