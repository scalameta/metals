package tests

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.FolderReportsZippper
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportFileName
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.TimeFormatter
import scala.meta.internal.metals.ZipReportsProvider
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.doctor.TargetsInfoProvider
import scala.meta.io.AbsolutePath

class ReportsSuite extends BaseSuite {
  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))
  val reportsProvider =
    new StdReportContext(workspace.toNIO, _.map(_ => "build-target"))

  val targetsInfoProvider: TargetsInfoProvider = new TargetsInfoProvider {
    def getTargetsInfoForReports(): List[Map[String, String]] =
      List(
        Map("type" -> "scala 3", "semanticdb" -> Icons.unicode.check),
        Map("type" -> "scala 2", "semanticdb" -> Icons.unicode.check),
      )
  }

  val folderReportsZippper: FolderReportsZippper =
    FolderReportsZippper(targetsInfoProvider, reportsProvider)

  def exampleText(workspaceStr: String = workspace.toString()): String =
    s"""|An error occurred in the file:
        |${workspaceStr}/WrongFile.scala
        |""".stripMargin

  def exampleReport(name: String, path: Option[URI] = None): Report =
    Report(name, exampleText(), "Test error report.", path)

  override def afterEach(context: AfterEach): Unit = {
    reportsProvider.deleteAll()
    super.afterEach(context)
  }

  test("create-report") {
    val path =
      reportsProvider.incognito.create(exampleReport("test_error"))
    val obtained =
      new String(Files.readAllBytes(path.get), StandardCharsets.UTF_8)
    assertNoDiff(
      s"""|${exampleText(StdReportContext.WORKSPACE_STR)}
          |#### Short summary:
          |
          |Test error report.
          |""".stripMargin,
      obtained,
    )
    assert(reportsProvider.incognito.getReports().length == 1)
    val dirsWithDate =
      reportsProvider.reportsDir.resolve("metals").toFile().listFiles()
    assert(dirsWithDate.length == 1)
    assert(
      dirsWithDate.forall(d =>
        d.isDirectory() && TimeFormatter.hasDateName(d.getName())
      )
    )
  }

  test("get-name-summary-and-buildTarget") {
    val report = exampleReport("test_error")
    val report2 =
      exampleReport("test_error2", Some(URI.create("file://file.scala")))
    reportsProvider.incognito.create(report)
    reportsProvider.incognito.create(report2)
    val reports = reportsProvider.incognito
      .getReports()
      .map { report =>
        val (name, buildTarget) =
          ReportFileName.getReportNameAndBuildTarget(report)
        val summary = Doctor.getErrorReportSummary(report, workspace)
        name -> (buildTarget, summary)
      }
      .sortBy(_._1)
    assertEquals(
      reports,
      List(
        "test_error2__build_target_" -> (None, Some(report2.shortSummary)),
        "test_error_" -> (None, Some(report.shortSummary)),
      ),
    )
  }

  test("delete-old-reports") {
    reportsProvider.incognito.create(
      exampleReport("some_test_error_old")
    )
    reportsProvider.incognito.create(
      exampleReport("some_different_test_error_old")
    )
    Thread.sleep(2) // to make sure, that the new tests have a later timestamp
    reportsProvider.incognito.create(
      exampleReport("some_test_error_new")
    )
    reportsProvider.incognito.create(
      exampleReport("some_different_test_error_new")
    )
    val deleted = reportsProvider.incognito.cleanUpOldReports(2)
    deleted match {
      case (_ :: _ :: Nil) if deleted.forall(_.name.contains("old")) =>
      case _ => fail(s"deleted: ${deleted.map(_.name)}")
    }
    val reports = reportsProvider.incognito.getReports()
    reports match {
      case (_ :: _ :: Nil) if reports.forall(_.name.contains("new")) =>
      case _ => fail(s"reports: ${reports.map(_.name)}")
    }
  }

  test("save-with-id") {
    val testId = "test-id"
    val path = reportsProvider.incognito
      .create(
        Report("test_error", exampleText(), "Test error", id = Some(testId))
      )
      .map(_.toRealPath())
    val obtained =
      new String(Files.readAllBytes(path.get), StandardCharsets.UTF_8)
    assertNoDiff(
      s"""|error id: $testId
          |${exampleText(StdReportContext.WORKSPACE_STR)}
          |#### Short summary:
          |
          |Test error
          |""".stripMargin,
      obtained,
    )
    val none1 = reportsProvider.incognito.create(
      Report("test_error_again", exampleText(), "Test error", id = Some(testId))
    )
    assertEquals(
      none1.map(_.toRealPath()),
      path,
    ) // check that it returns the path to the original report
    val newReportsProvider =
      new StdReportContext(workspace.toNIO, _ => Some("buildTarget"))
    val none2 = newReportsProvider.incognito.create(
      Report("test_error_again", exampleText(), "Test error", id = Some(testId))
    )
    assertEquals(none2.map(_.toRealPath()), path)
    val reports = newReportsProvider.incognito.getReports()
    reports match {
      case head :: Nil => assert(head.file.getName == path.get.toFile.getName)
      case _ => fail(s"reports: ${reports.map(_.name)}")
    }
  }

  test("zip-reports") {
    reportsProvider.incognito.create(exampleReport("test_error"))
    reportsProvider.incognito.create(exampleReport("different_test_error"))
    val pathToReadMe = ZipReportsProvider.zip(List(folderReportsZippper))
    val zipPath =
      reportsProvider.reportsDir.resolve(StdReportContext.ZIP_FILE_NAME)
    assert(Files.exists(zipPath))
    assert(Files.exists(pathToReadMe.toNIO))
    Files.delete(pathToReadMe.toNIO)
  }
}
